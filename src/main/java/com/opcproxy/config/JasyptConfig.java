package com.opcproxy.config;

import org.jasypt.encryption.StringEncryptor;
import org.jasypt.iv.RandomIvGenerator;
import org.jasypt.salt.RandomSaltGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Кастомный StringEncryptor для jasypt-spring-boot (ТЗ §7.3).
 *
 * Master-ключ берётся из:
 *   1) переменной окружения JASYPT_MASTER_PASSWORD (основной способ),
 *   2) либо системного свойства -Djasypt.encryptor.password=... (Run Config IDEA).
 *
 * ВАЖНО: master-ключ НЕ хранится в application.yml и не коммитится.
 * Потеря ключа = невозможность расшифровать пароли подключений (пересоздать их).
 */
@Configuration
public class JasyptConfig {

    @Bean("jasyptStringEncryptor")
    @Primary
    public StringEncryptor stringEncryptor() {
        String master = System.getenv("JASYPT_MASTER_PASSWORD");
        if (master == null || master.isBlank()) {
            master = System.getProperty("jasypt.encryptor.password");
        }
        if (master == null || master.isBlank()) {
            throw new IllegalStateException("""
                    JASYPT_MASTER_PASSWORD не задан.
                    Задайте переменную окружения (Run Config -> Environment variables):
                      JASYPT_MASTER_PASSWORD=<ваш мастер-пароль>
                    Шифрование/расшифровка паролей подключений невозможна без ключа.""");
        }

        var encryptor = new org.jasypt.encryption.pbe.PooledPBEStringEncryptor();
        var config = new org.jasypt.encryption.pbe.config.SimpleStringPBEConfig();
        config.setPassword(master);
        config.setAlgorithm("PBEWITHHMACSHA512ANDAES_256");
        config.setKeyObtentionIterations("1000");
        config.setPoolSize("1");
        config.setProviderName("SunJCE");
        config.setSaltGenerator(new RandomSaltGenerator());
        config.setIvGenerator(new RandomIvGenerator());
        config.setStringOutputType("base64");
        encryptor.setConfig(config);
        return encryptor;
    }
}