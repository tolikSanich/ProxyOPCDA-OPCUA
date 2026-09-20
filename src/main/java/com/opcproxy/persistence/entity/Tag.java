package com.opcproxy.persistence.entity;

import com.opcproxy.persistence.audit.Auditable;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.ReadMode;
import com.opcproxy.persistence.enums.SourceType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@AllArgsConstructor
@NoArgsConstructor
@Data
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@Entity
@Table(name = "tag")
public class Tag implements Auditable {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @EqualsAndHashCode.Include
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false)
    private SourceType sourceType;

    @ManyToOne(fetch = FetchType.EAGER)
    @JoinColumn(name = "connection_id")
    private OpcDaConnection connection;

    @Column(name = "source_item_id", length = 512)
    private String sourceItemId;

    @Column(columnDefinition = "TEXT")
    private String expression;

    @Enumerated(EnumType.STRING)
    @Column(name = "data_type", nullable = false)
    private DataType dataType;

    @Enumerated(EnumType.STRING)
    @Column(name = "read_mode", nullable = false)
    private ReadMode readMode = ReadMode.ASYNC;

    @Column(name = "refresh_period_ms", nullable = false)
    private Integer refreshPeriodMs = 1000;

    @Column(name = "ua_sampling_interval_ms")
    private Integer uaSamplingIntervalMs;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "interval_profile_id")
    private IntervalProfile intervalProfile;

    @Column(name = "publish_mqtt", nullable = false)
    private Boolean publishMqtt = false;

    @Column(name = "mqtt_deadband")
    private Float mqttDeadband;

    private String description;

    @Column(nullable = false)
    private Boolean enabled = true;

    @Version
    private Integer version;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist protected void onCreate() { createdAt = updatedAt = LocalDateTime.now(); }
    @PreUpdate protected void onUpdate() { updatedAt = LocalDateTime.now(); }

}