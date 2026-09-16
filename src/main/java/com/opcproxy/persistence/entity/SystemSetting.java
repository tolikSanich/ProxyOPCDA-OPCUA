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
@Entity
@Table(name = "system_setting")
public class SystemSetting {
    @Id
    private String key;
    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
    @PrePersist @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}

