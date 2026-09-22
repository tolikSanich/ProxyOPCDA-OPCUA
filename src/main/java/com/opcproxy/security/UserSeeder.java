// src/main/java/com/opcproxy/security/UserSeeder.java
package com.opcproxy.security;

import com.opcproxy.persistence.entity.UserAccount;
import com.opcproxy.persistence.enums.UserRole;
import com.opcproxy.persistence.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Component
@RequiredArgsConstructor
public class UserSeeder {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void seedDefaultAdmin() {
        if (userRepository.count() == 0) {
            UserAccount admin = new UserAccount();
            admin.setUsername("admin");
            admin.setPasswordHash(passwordEncoder.encode("admin"));
            admin.setRole(UserRole.ADMIN);
            admin.setEnabled(true);
            admin.setForcePasswordChange(true); // Требует смены при первом входе

            userRepository.save(admin);
            log.info("✅ Создан пользователь по умолчанию: admin / admin (требуется смена пароля)");
        } else {
            log.info("✅ База пользователей не пуста. Сидинг пропущен.");
        }
    }
}