package com.opcproxy.ui.services;

import com.opcproxy.opcda.ConnectionState;
import com.opcproxy.opcda.OpcDaClient;
import com.opcproxy.opcda.OpcDaConnectionManager;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.security.PasswordCipher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class ConnectionService {

    private final OpcDaConnectionRepository connectionRepository;
    private final OpcDaConnectionManager connectionManager;
    private final PasswordCipher passwordCipher;   // <-- ДОБАВИТЬ (или import)

    public List<OpcDaConnection> findAll() {
        return connectionRepository.findAll();
    }

    public Optional<OpcDaConnection> findById(Long id) {
        return connectionRepository.findById(id);
    }

    public boolean existsByName(String name) {
        return connectionRepository.existsByName(name);
    }

    @Transactional
    public OpcDaConnection save(OpcDaConnection connection) {
        // Шифруем пароль, если пользователь ввёл новый (plaintext).
        // Зашифрованное значение (ENC(...)) не трогаем — это read-back из БД.
        if (connection.getPasswordEncrypted() != null
                && !passwordCipher.isEncrypted(connection.getPasswordEncrypted())) {
            connection.setPasswordEncrypted(
                    passwordCipher.encrypt(connection.getPasswordEncrypted()));
        }
        OpcDaConnection saved = connectionRepository.save(connection);

        if (Boolean.TRUE.equals(saved.getEnabled())) {
            // Переподключаем с актуальной конфигурацией.
            // disconnect старого клиента нужен, чтобы подхватить изменённые
            // host/CLSID/учётные данные, а не переиспользовать старую сессию.
            connectionManager.disconnect(saved.getId());
            if (connectionManager.tryConnect(saved)) {
                log.info("Connection '{}' (re)connected after save", saved.getName());
            } else {
                // Не блокируем сохранение: клиент попадёт в цикл реконнекта с backoff
                // и подключится, как только сервер станет доступен
                log.warn("Connection '{}' saved but not connected — background reconnect scheduled",
                        saved.getName());
            }
        } else {
            connectionManager.disconnect(saved.getId());
        }

        return saved;
    }
    @Transactional
    public void setEnabled(Long id, boolean enabled) {
        connectionRepository.findById(id).ifPresent(conn -> {
            conn.setEnabled(enabled);
            connectionRepository.save(conn);
            if (enabled) {
                connectionManager.disconnect(id);
                connectionManager.tryConnect(conn);
            } else {
                connectionManager.disconnect(id);
            }
        });
    }
    @Transactional
    public void delete(Long id) {
        connectionManager.disconnect(id);
        connectionRepository.deleteById(id);
    }

    public ConnectionState getStatus(Long id) {
        return connectionManager.getClient(id)
                .map(OpcDaClient::getState)
                .orElse(ConnectionState.DISCONNECTED);
    }

    public int getErrorCount(Long id) {
        return connectionManager.getClient(id)
                .map(OpcDaClient::getErrorCount)
                .orElse(0);
    }

    public String testConnection(OpcDaConnection connection) {
        OpcDaClient testClient = new OpcDaClient(connection, passwordCipher);
        try {
            testClient.connect();
            testClient.disconnect();
            return "Подключение успешно установлено";
        } catch (Exception e) {
            log.error("Test connection failed for: {}", connection.getName(), e);

            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("0xC0000001") || errorMsg.contains("SmbException"))) {
                return "⚠️ Ошибка DCOM/SMB: Windows блокирует доступ к реестру.\n\n" +
                        "Решение 1 (быстрое): Используйте CLSID (GUID) вместо ProgID.\n" +
                        "Решение 2: Выполните в PowerShell (Admin):\n" +
                        "New-ItemProperty -Path 'HKLM:\\...\\System' -Name 'LocalAccountTokenFilterPolicy' -Value 1 -Force\n" +
                        "и перезагрузите ПК.";
            } else if (errorMsg != null && errorMsg.contains("Class not registered")) {
                return "❌ Ошибка: ProgID/CLSID не найден в реестре Windows.";
            } else if (errorMsg != null && errorMsg.contains("Access is denied")) {
                return "🔒 Ошибка доступа: Проверьте логин (формат '.\\User'), пароль и права DCOM (dcomcnfg).";
            }

            return "Ошибка подключения: " + e.getMessage();
        }
    }
    public void reconnect(Long id) {
        connectionManager.reconnect(id);
    }
}