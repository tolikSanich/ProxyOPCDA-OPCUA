// src/main/java/com/opcproxy/persistence/audit/AuditEvent.java
package com.opcproxy.persistence.audit;

/**
 * Событие аудита для асинхронной обработки.
 * ТЗ §5.10.5: Разрывает циклическую зависимость между Hibernate Interceptor и JPA Repository.
 */
public record AuditEvent(
        Long entityId,
        String entityType,
        String action,
        String oldValue,
        String newValue
) {}