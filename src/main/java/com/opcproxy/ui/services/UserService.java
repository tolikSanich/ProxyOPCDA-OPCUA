package com.opcproxy.ui.services;

import com.opcproxy.persistence.entity.UserAccount;
import com.opcproxy.persistence.enums.UserRole;
import com.opcproxy.persistence.repository.UserAccountRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/**
 * Сервис управления пользователями (ТЗ §5.8.5, §7.3).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserAccountRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public List<UserAccount> findAll() {
        return userRepository.findAll();
    }

    public Optional<UserAccount> findById(Long id) {
        return userRepository.findById(id);
    }

    public Optional<UserAccount> findByUsername(String username) {
        return userRepository.findByUsername(username);
    }

    public boolean existsByUsername(String username) {
        return userRepository.existsByUsername(username);
    }

    @Transactional
    public UserAccount save(UserAccount user, String rawPassword) {
        // Проверка уникальности имени
        if (user.getId() == null && existsByUsername(user.getUsername())) {
            throw new IllegalArgumentException("Username already exists: " + user.getUsername());
        }

        // Логика хэширования пароля
        if (rawPassword != null && !rawPassword.isBlank()) {
            // Новый пароль введён — хэшируем
            user.setPasswordHash(passwordEncoder.encode(rawPassword));
            log.debug("Password hashed for user: {}", user.getUsername());
        } else if (user.getId() != null) {
            // Редактирование без изменения пароля — оставляем старый хэш
            userRepository.findById(user.getId())
                    .ifPresent(old -> user.setPasswordHash(old.getPasswordHash()));
        } else {
            // Новый пользователь без пароля — ошибка
            throw new IllegalArgumentException("Password is required for new user");
        }

        UserAccount saved = userRepository.save(user);
        log.info("User saved: {} (role={})", saved.getUsername(), saved.getRole());
        return saved;
    }

    @Transactional
    public void delete(Long id) {
        UserAccount user = userRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + id));

        if (user.getRole() == UserRole.ADMIN) {
            long adminCount = userRepository.findAll().stream()
                    .filter(u -> u.getRole() == UserRole.ADMIN && u.getEnabled())
                    .count();
            if (adminCount <= 1) {
                throw new IllegalStateException("Cannot delete last admin user");
            }
        }

        userRepository.deleteById(id);
        log.info("User deleted: {}", user.getUsername());
    }

    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        userRepository.findById(id).ifPresent(user -> {
            user.setEnabled(enabled);
            userRepository.save(user);
            log.info("User {} {}", user.getUsername(), enabled ? "enabled" : "disabled");
        });
    }

    @Transactional
    public void changePassword(String username, String oldPassword, String newPassword) {
        UserAccount user = userRepository.findByUsername(username)
                .orElseThrow(() -> new IllegalArgumentException("Пользователь не найден"));

        if (!passwordEncoder.matches(oldPassword, user.getPasswordHash())) {
            throw new IllegalArgumentException("Неверный текущий пароль");
        }

        if (newPassword == null || newPassword.length() < 6) {
            throw new IllegalArgumentException("Новый пароль должен быть не менее 6 символов");
        }

        user.setPasswordHash(passwordEncoder.encode(newPassword));
        user.setForcePasswordChange(false); // Снимаем флаг принудительной смены
        userRepository.save(user);

        log.info("Пароль успешно изменен для пользователя");
    }
}