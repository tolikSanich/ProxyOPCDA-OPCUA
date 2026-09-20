package com.opcproxy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.opcua")
public class OpcUaConfig {
    private int port = 4840;

    private String hostname = "localhost";

    private String applicationName = "OPC DA-UA Proxy";

    private String applicationUri = "urn:opcproxy:ua:application";

    private String namespaceUri = "urn:opcproxy:ua";

    private int minPublishingIntervalMs = 100;

    /** true = «Доверять всем» — принимать любые клиентские сертификаты (небезопасно, для стенда). */
    private boolean trustAllClients = true;
    private int defaultSamplingIntervalMs = 1000;
    /** Включённые политики безопасности (аналог чек-боксов arOPC). */
    /** Политики безопасности защищённых endpoint'ов (аналог чек-боксов arOPC). */
    private java.util.List<String> enabledPolicies =
            java.util.List.of("None", "Basic256Sha256");
    private String trustStoreDir = "pki/trusted";
    private String rejectedStoreDir = "pki/rejected";
}