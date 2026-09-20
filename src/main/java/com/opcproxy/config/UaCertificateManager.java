package com.opcproxy.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Files;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Collections;

/**
 * Управление сертификатом UA-сервера (ТЗ §5.5.2).
 * Генерация — штатным keytool JDK (без BouncyCastle: ТЗ запрещает вторую версию BC).
 * Хранение: {pki-dir}/server.pfx (PKCS12).
 * SAN включает URI:ApplicationUri — обязательное требование OPC UA.
 */
@Slf4j
@Component
public class UaCertificateManager {

    @Value("${app.opcua.pki-dir:pki}")
    private String pkiDir;
    @Value("${app.opcua.application-uri:urn:opcproxy:ua:application}")
    private String applicationUri;
    @Value("${app.opcua.hostname:localhost}")
    private String hostname;
    @Value("${app.opcua.keystore-password:changeit}")
    private String password;

    private KeyPair keyPair;
    private X509Certificate certificate;

    public synchronized void ensureCertificate() throws Exception {
        File ks = new File(pkiDir, "server.pfx");
        Files.createDirectories(ks.getParentFile().toPath());

        if (!ks.exists()) {
            log.info("Generating self-signed UA server certificate via keytool...");
            String dname = "CN=OPC DA-UA Proxy, O=OPCProxy";
            String san = "dns:" + hostname + ",dns:localhost,ip:127.0.0.1,uri:" + applicationUri;

            Process p = new ProcessBuilder(
                    System.getProperty("java.home") + File.separator + "bin" + File.separator + "keytool",
                    "-genkeypair",
                    "-alias", "server",
                    "-keyalg", "RSA", "-keysize", "2048",
                    "-validity", "3650",
                    "-dname", dname,
                    "-ext", "SAN=" + san,
                    "-ext", "bc=ca:false",
                    "-ext", "ku=digitalSignature,keyEncipherment",
                    "-ext", "eku=serverAuth,clientAuth",
                    "-storetype", "PKCS12",
                    "-keystore", ks.getAbsolutePath(),
                    "-storepass", password,
                    "-keypass", password
            ).inheritIO().start();
            if (p.waitFor() != 0) {
                throw new IllegalStateException("keytool failed, exit=" + p.exitValue());
            }
            log.info("UA certificate created: {}", ks.getAbsolutePath());
        }

        var store = java.security.KeyStore.getInstance("PKCS12");
        try (var in = Files.newInputStream(ks.toPath())) {
            store.load(in, password.toCharArray());
        }
        PrivateKey key = (PrivateKey) store.getKey("server", password.toCharArray());
        X509Certificate cert = (X509Certificate) store.getCertificate("server");
        this.keyPair = new KeyPair(cert.getPublicKey(), key);
        this.certificate = cert;
        log.info("UA certificate loaded: subject={}, SAN applicationUri={}",
                cert.getSubjectX500Principal().getName(), applicationUri);
    }

    public KeyPair getKeyPair() { return keyPair; }
    public X509Certificate getCertificate() { return certificate; }
}