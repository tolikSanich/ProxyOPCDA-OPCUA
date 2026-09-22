package com.opcproxy.persistence.audit;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.hibernate.Interceptor;
import org.hibernate.type.Type;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Hibernate Interceptor для автоматического аудита изменений сущностей.
 * ТЗ §5.10.5: перехватывает CREATE/UPDATE/DELETE для сущностей, реализующих Auditable.
 *
 * ВАЖНО: В Hibernate 6+ класс EmptyInterceptor удалён. Реализуем интерфейс Interceptor напрямую,
 * используя default-методы интерфейса для игнорирования ненужных событий.
 * Параметр id имеет тип Object (не Serializable, как в Hibernate 5).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AuditInterceptor implements Interceptor {

    private final ApplicationEventPublisher eventPublisher;

    @Override
    public boolean onPersist(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        if (entity instanceof Auditable) {
            String entityType = entity.getClass().getSimpleName();
            eventPublisher.publishEvent(new AuditEvent(
                    (Long) id, entityType, "CREATE", null, formatState(propertyNames, state)
            ));
        }
        return Interceptor.super.onPersist(entity, id, state, propertyNames, types);
    }

    @Override
    public void onRemove(Object entity, Object id, Object[] state, String[] propertyNames, Type[] types) {
        if (entity instanceof Auditable) {
            String entityType = entity.getClass().getSimpleName();
            eventPublisher.publishEvent(new AuditEvent(
                    (Long) id, entityType, "DELETE", formatState(propertyNames, state), null
            ));
        }
        Interceptor.super.onRemove(entity, id, state, propertyNames, types);
    }

    @Override
    public boolean onFlushDirty(Object entity, Object id, Object[] currentState, Object[] previousState, String[] propertyNames, Type[] types) {
        if (entity instanceof Auditable) {
            String entityType = entity.getClass().getSimpleName();
            eventPublisher.publishEvent(new AuditEvent(
                    (Long) id, entityType, "UPDATE", formatState(propertyNames, previousState), formatState(propertyNames, currentState)
            ));
        }
        return Interceptor.super.onFlushDirty(entity, id, currentState, previousState, propertyNames, types);
    }

    private String formatState(String[] propertyNames, Object[] state) {
        if (propertyNames == null || state == null) return null;
        return Arrays.stream(propertyNames)
                .limit(state.length)
                .collect(Collectors.joining(", ", "{", "}"));
    }
}