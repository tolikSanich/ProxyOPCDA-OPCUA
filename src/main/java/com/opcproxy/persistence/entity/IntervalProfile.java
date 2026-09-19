package com.opcproxy.persistence.entity;

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
@Table(name = "interval_profile",
        uniqueConstraints = @UniqueConstraint(name = "uq_interval_profile_name", columnNames = "name"))
public class IntervalProfile {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "ua_sampling_interval_ms", nullable = false)
    private Integer uaSamplingIntervalMs = 1000;

    @Column(name = "mqtt_publish_interval_ms")
    private Integer mqttPublishIntervalMs;

    @Column(name = "mqtt_deadband")
    private Float mqttDeadband;

    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = updatedAt = LocalDateTime.now();
    }
    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

}