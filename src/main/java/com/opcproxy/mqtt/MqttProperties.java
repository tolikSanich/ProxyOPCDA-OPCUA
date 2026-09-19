package com.opcproxy.mqtt;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app.mqtt")
public class MqttProperties {
    private boolean enabled = false;
    private String broker = "tcp://localhost:1883";
    private String clientId = "opc-da-ua-gateway";
    private String baseTopic = "gateway/default";
    private int qos = 1;
    private boolean retain = false;
    private long configRefreshMs = 5000;
    /** Опциональная аутентификация (null = анонимно). */
    private String username;
    private String password;
    private int bufferCapacity = 10000;
    private long publishIntervalMs = 0;
}