// src/main/java/com/opcproxy/persistence/audit/Auditable.java
package com.opcproxy.persistence.audit;

/**
 * Маркерный интерфейс для сущностей, изменения которых должны логироваться в audit_log.
 * ТЗ §5.10.5: "Все изменения конфигурации должны журналироваться".
 */
public interface Auditable {
    Long getId();
}