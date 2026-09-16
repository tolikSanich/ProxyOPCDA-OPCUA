package com.opcproxy.opcua;

import com.opcproxy.config.OpcUaConfig;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.server.EndpointConfig;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.OpcUaServerConfig;
import org.eclipse.milo.opcua.sdk.server.identity.AnonymousIdentityValidator;
import org.eclipse.milo.opcua.sdk.server.identity.CompositeValidator;
import org.eclipse.milo.opcua.sdk.server.identity.UsernameIdentityValidator;
import org.eclipse.milo.opcua.stack.core.security.SecurityPolicy;
import org.eclipse.milo.opcua.stack.core.transport.TransportProfile;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.enumerated.MessageSecurityMode;
import org.eclipse.milo.opcua.stack.transport.server.OpcServerTransportFactory;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransport;
import org.eclipse.milo.opcua.stack.transport.server.tcp.OpcTcpServerTransportConfig;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class OpcUaServerManager implements DisposableBean {

    private final OpcUaConfig config;
    private final AddressSpaceBuilder addressSpaceBuilder;

    @Getter
    private volatile OpcUaServer server;

    private volatile boolean started = false;

    @EventListener(ApplicationReadyEvent.class)
    public void start() throws Exception {
        if (started) return;
        started = true;
        doStart();
    }

    private void doStart() throws Exception {
        if (server != null) {
            log.warn("OPC UA server already started");
            return;
        }

        UsernameIdentityValidator usernameValidator = new UsernameIdentityValidator(
                authChallenge -> "opcuser".equals(authChallenge.getUsername()) && "opcpassword".equals(authChallenge.getPassword())
        );

        OpcUaServerConfig serverConfig = OpcUaServerConfig.builder()
                .setApplicationName(LocalizedText.english(config.getApplicationName()))
                .setApplicationUri(config.getApplicationUri())
                .setProductUri(config.getApplicationUri())
                .setIdentityValidator(new CompositeValidator(new AnonymousIdentityValidator(), usernameValidator))
                .setEndpoints(createEndpointConfigs())
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
    public void destroy() throws Exception {
        stop();
    }

    private Set<EndpointConfig> createEndpointConfigs() {
        Set<EndpointConfig> endpoints = new LinkedHashSet<>();
        endpoints.add(
                EndpointConfig.newBuilder()
                        .setHostname(hostname())
                        .setBindAddress("0.0.0.0")
                        .setBindPort(config.getPort())
                        .setTransportProfile(TransportProfile.TCP_UASC_UABINARY)
                        .setSecurityPolicy(SecurityPolicy.None)
                        .setSecurityMode(MessageSecurityMode.None)
                        .build()
        );
        return endpoints;
    }

    private static String hostname() {
        try { return InetAddress.getLocalHost().getHostName(); }
        catch (Exception e) { return "localhost"; }
    }
}