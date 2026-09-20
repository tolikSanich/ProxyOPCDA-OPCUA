package com.opcproxy.persistence.entity;

import com.opcproxy.persistence.audit.Auditable;
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
public class SystemSetting implements Auditable {
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
    @Override
    public Long getId() {
        return key != null ? (long) key.hashCode() : null;
    }
}

