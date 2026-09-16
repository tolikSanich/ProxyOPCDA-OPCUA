package com.opcproxy.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConfigurationProperties(prefix = "app.opcda")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OpcDaConfig {
    @Builder.Default
    private int defaultRefreshPeriodMs = 1000;
    @Builder.Default
    private int defaultReconnectIntervalMs = 5000;
    @Builder.Default
    private int maxConnections = 10;
    @Builder.Default
    private int connectionTimeoutMs = 10000;
    @Builder.Default
    private boolean enableAsyncByDefault = true;

}