package com.opcproxy.opcua;

import com.opcproxy.config.OpcUaConfig;
import com.opcproxy.config.UaCertificateManager;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.asn1.x500.X500Name;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.security.*;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.ByteString;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.transport.server.OpcServerTransportFactory;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpcUaServerManager implements DisposableBean {

    private final OpcUaConfig config;
    private final AddressSpaceBuilder addressSpaceBuilder;
    private final UaCertificateManager uaCertificateManager;  // Lombok подхватит
    @Getter
    private volatile OpcUaServer server;

    private volatile boolean started = false;

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        if (started) return;
        started = true;
        doStart();
    }
    public String getHostname() {
        return hostname();
    }

    private void doStart() throws Exception {
        if (server != null) {
            log.warn("OPC UA server already started");
            return;
        }

        // ТЗ §5.5.2: сертификат генерируется при первом запуске (keytool, без BC)
        uaCertificateManager.ensureCertificate();

        // --- Сертификат сервера: кладём наш keytool-сертификат в store
        //     ДО инициализации группы (иначе group.initialize() не найдёт его) ---
        var certificateStore = new MemoryCertificateStore();
        var entry = new CertificateStore.Entry(
                uaCertificateManager.getKeyPair().getPrivate(),
                new X509Certificate[]{ uaCertificateManager.getCertificate() });
        certificateStore.set(
               NodeIds.RsaSha256ApplicationCertificateType,
                entry);
        var trustListManager = new MemoryTrustListManager();

        // «Доверять всем» (аналог чекбокса arOPC, для стенда). Прод: DefaultClientCertificateValidator + trust-list.
        var certificateValidator = new CertificateValidator.InsecureCertificateValidator();
        CertificateFactory certificateFactory =
                new CertificateFactory() {
                    @Override public java.security.KeyPair createKeyPair(
                            NodeId certificateTypeId) {
                        throw new UnsupportedOperationException(
                                "Certificate is pre-provisioned via keytool (UaCertificateManager)");
                    }
                    @Override public java.security.cert.X509Certificate[] createCertificateChain(
                            NodeId certificateTypeId,
                            KeyPair keyPair) {
                        throw new UnsupportedOperationException(
                                "Certificate is pre-provisioned via keytool (UaCertificateManager)");
                    }
                    @Override public ByteString createSigningRequest(
                            NodeId certificateTypeId,
                            KeyPair keyPair,
                            X500Name subjectName,
                            String sanUri, List<String> dnsNames, List<String> ipAddresses) {
                        throw new UnsupportedOperationException(
                                "CSR not supported for keytool-provisioned certificate");
                    }
                };
        var group = DefaultApplicationGroup.createAndInitialize(
                trustListManager, certificateStore, certificateFactory, certificateValidator);
        var certificateManager = new DefaultCertificateManager(
                new MemoryCertificateQuarantine(), group);

        UsernameIdentityValidator usernameValidator = new UsernameIdentityValidator(   // ДО использования!
                auth -> "opcuser".equals(auth.getUsername())
                        && "opcpassword".equals(auth.getPassword()));

        OpcUaServerConfig serverConfig = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english(config.getApplicationName()))
                .setApplicationUri(config.getApplicationUri())
                .setProductUri(config.getApplicationUri())
                .setIdentityValidator(new CompositeValidator(
                        new AnonymousIdentityValidator(), usernameValidator))
                .setCertificateManager(certificateManager)
                .setEndpoints(createEndpointConfigs(certificateManager))
                .build();



        OpcServerTransportFactory transportFactory = transportProfile -> {
            if (transportProfile == TransportProfile.TCP_UASC_UABINARY) {
                return new OpcTcpServerTransport(OpcTcpServerTransportConfig.newBuilder().build());
            }
            throw new IllegalArgumentException("Unsupported transport: " + transportProfile);
        };

        server = new OpcUaServer(serverConfig, transportFactory);
        addressSpaceBuilder.build(server);

        try {
            server.startup().get();
            log.info("OPC UA server started at opc.tcp://{}:{}", hostname(), config.getPort());
        } catch (Exception e) {
            server = null;
            log.error("OPC UA server failed to start (port {} busy?): {}. " +
                    "Gateway continues without OPC UA server.", config.getPort(), e.getMessage());
            // не пробрасываем — приложение живёт, DA-слой работает
        }
    }

    public synchronized void stop() {
        OpcUaServer s = server;
        server = null;
        if (s != null) {
            try {
                s.shutdown().get();
                log.info("OPC UA server stopped");
            } catch (Exception e) {
                log.warn("Error during OPC UA server shutdown (server may not have been started): {}", e.getMessage());
            }
        }
    }

    public boolean isRunning() { return server != null; }
    public int getBindPort() { return config.getPort(); }

    @Override
    public void destroy() {
        stop();
    }

    private Set<EndpointConfig> createEndpointConfigs(CertificateManager certificateManager) {
        Set<EndpointConfig> endpoints = new LinkedHashSet<>();

        // None — всегда
        endpoints.add(EndpointConfig.newBuilder()
                .setHostname(hostname())
                .setBindAddress("0.0.0.0")
                .setBindPort(config.getPort())
                .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                .setSecurityPolicy(SecurityPolicy.None)
                .setSecurityMode(MessageSecurityMode.None)
                .build());

        // Защищённые — только если сертификат готов (ошибка [143]: был локальный certificateManager)
        if (uaCertificateManager.getCertificate() != null) {
            for (String name : config.getEnabledPolicies()) {
                SecurityPolicy policy = SecurityPolicy.valueOf(name.trim());
                if (policy == SecurityPolicy.None) continue;
                endpoints.add(EndpointConfig.newBuilder()
                        .setHostname(hostname())
                        .setBindAddress("0.0.0.0")
                        .setBindPort(config.getPort())
                        .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                        .setSecurityPolicy(policy)
                        .setSecurityMode(MessageSecurityMode.SignAndEncrypt)
                        .setCertificate(uaCertificateManager.getCertificate())
                        .build());
            }
            log.info("UA secure endpoints enabled: {}", config.getEnabledPolicies());
        }
        return endpoints;
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "localhost"; }
    }
}