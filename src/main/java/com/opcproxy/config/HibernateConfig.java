package com.opcproxy.config;

import org.springframework.context.annotation.Configuration;

/**
 * Конфигурация Hibernate.
 * ВАЖНО: AuditInterceptor регистрируется автоматически Spring Boot,
 * так как он помечен как @Component и реализует org.hibernate.Interceptor.
 * Ручная регистрация LocalContainerEntityManagerFactoryBean удалена
 * во избежание циклических зависимостей и конфликтов с автоконфигурацией.
 */
@Configuration
public class HibernateConfig {
    // Пустой класс-маркер, если потребуется добавить специфичные свойства Hibernate в будущем.
}