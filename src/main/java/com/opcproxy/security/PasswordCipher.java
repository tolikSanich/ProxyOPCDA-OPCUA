package com.opcproxy.security;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jasypt.encryption.StringEncryptor;
import org.springframework.stereotype.Component;

/**
 * Шифрование паролей подключений (ТЗ §7.3).
 * Формат хранения: "ENC(<base64>)". Plaintext допускается только как
 * legacy-вход миграции и шифруется при первом чтении.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PasswordCipher {

    public static final String PREFIX = "ENC(";
    public static final String SUFFIX = ")";

    private final StringEncryptor encryptor;   // бин "jasyptStringEncryptor"

    /** Шифрует plaintext и оборачивает в ENC(...). Уже зашифрованное не трогает. */
    public String encrypt(String plain) {
        if (plain == null || plain.isBlank()) return plain;
        if (isEncrypted(plain)) return plain;                  // idempotent
        return PREFIX + encryptor.encrypt(plain) + SUFFIX;
    }

    /**
     * Расшифровывает ENC(...) для использования DCOM-клиентом.
     * Plaintext (legacy) возвращает как есть — с WARN в лог.
     */
    public String decrypt(String stored) {
        if (stored == null || stored.isBlank()) return stored;
        if (!isEncrypted(stored)) {
            log.warn("Found PLAINTEXT password in DB (legacy) — will be encrypted on next save");
            return stored;
        }
        String body = stored.substring(PREFIX.length(), stored.length() - SUFFIX.length());
        return encryptor.decrypt(body);
    }

    public boolean isEncrypted(String stored) {
        return stored != null && stored.startsWith(PREFIX) && stored.endsWith(SUFFIX);
    }

    /** Безопасное представление для UI/логов: никогда не отдаём сам пароль. */
    public String mask(String stored) {
        return (stored == null || stored.isBlank()) ? "" : "••••••••(установлен)";
    }
}