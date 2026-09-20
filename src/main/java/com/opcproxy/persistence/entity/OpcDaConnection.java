package com.opcproxy.persistence.entity;

import com.opcproxy.persistence.audit.Auditable;
import com.opcproxy.persistence.enums.ReadMode;
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
@Table(name = "opc_da_connection")
public class OpcDaConnection implements Auditable {

    @Id
    @EqualsAndHashCode.Include
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(nullable = false)
    private String host;

    @Column(name = "prog_id_or_clsid", nullable = false)
    private String progIdOrClsid;

    private String username;

    // ДОБАВЛЕНО: Явное указание колонки с дефолтным значением
    @Column(name = "domain", nullable = false, columnDefinition = "VARCHAR(255) DEFAULT ''")
    private String domain = "";

    @Column(name = "password_encrypted")
    private String passwordEncrypted;

    @Enumerated(EnumType.STRING)
    @Column(name = "default_read_mode", nullable = false)
    private ReadMode defaultReadMode = ReadMode.ASYNC;

    @Column(name = "default_refresh_period_ms", nullable = false)
    private Integer defaultRefreshPeriodMs = 1000;

    @Column(name = "reconnect_interval_ms", nullable = false)
    private Integer reconnectIntervalMs = 5000;

    @Column(nullable = false)
    private Boolean enabled = true;

    private String description;

    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}