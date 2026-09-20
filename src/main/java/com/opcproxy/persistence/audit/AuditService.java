package com.opcproxy.persistence.audit;

import com.opcproxy.persistence.entity.AuditLog;
import com.opcproxy.persistence.repository.AuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Сервис записи в audit_log (ТЗ §5.10.5).
 * Обрабатывает события AuditEvent асинхронно, не блокируя основной поток транзакции.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditLogRepository auditLogRepository;

    @Async
    @EventListener
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void handleAuditEvent(AuditEvent event) {
        try {
            AuditLog auditLog = new AuditLog();
            auditLog.setTimestamp(LocalDateTime.now());
            auditLog.setActor(getCurrentUsername());
            auditLog.setAction(event.action());
            auditLog.setEntityType(event.entityType());
            auditLog.setEntityId(event.entityId());
            auditLog.setOldValue(event.oldValue());
            auditLog.setNewValue(event.newValue());

            auditLogRepository.save(auditLog);
            log.debug("Audit saved: {} {} id={}", event.action(), event.entityType(), event.entityId());
        } catch (Exception e) {
            log.error("Failed to write audit log for {} {}: {}", event.action(), event.entityType(), e.getMessage());
        }
    }

    private String getCurrentUsername() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getName() != null && !"anonymousUser".equals(auth.getName())) {
            return auth.getName();
        }
        return "system";
    }
}