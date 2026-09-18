package com.opcproxy.security;

import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Разовая миграция: шифрует все plaintext-пароли подключений в ENC(...).
 * Повторный запуск безопасен (idempotent).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordMigrationRunner implements ApplicationRunner {

    private final OpcDaConnectionRepository repository;
    private final PasswordCipher cipher;

    @Override
    @Transactional
    public void run(ApplicationArguments args) {
        int migrated = 0;
        for (var conn : repository.findAll()) {
            String pwd = conn.getPasswordEncrypted();
            if (pwd != null && !pwd.isBlank() && !cipher.isEncrypted(pwd)) {
                conn.setPasswordEncrypted(cipher.encrypt(pwd));
                repository.save(conn);
                migrated++;
            }
        }
        if (migrated > 0) {
            log.info("Password migration: {} connections encrypted to ENC(...) format", migrated);
        }
    }
}