package com.opcproxy.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

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

    private int defaultSamplingIntervalMs = 1000;
}