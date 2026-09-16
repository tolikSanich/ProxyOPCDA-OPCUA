package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Менеджер подключений к OPC DA серверам.
 *
 * Возможности:
 *  - жизненный цикл подключений (connect / disconnect / reconnect);
 *  - активный health-check живых подключений (Server.getServerState());
 *  - автоматическое переподключение упавших;
 *  - подъём подключений из БД, не сумевших подключиться при старте;
 *  - экспоненциальный backoff для недоступных серверов (5с → 10с → ... → 60с),
 *    чтобы зависший connect не блокировал health-check и поллинг остальных.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpcDaConnectionManager {

    private final OpcDaConnectionRepository connectionRepository;

    /** Активные клиенты (ключ — ID подключения из БД). */
    private final Map<Long, OpcDaClient> clients = new ConcurrentHashMap<>();

    /** Время (epoch ms), раньше которого не предпринимать попыток подключения. */
    private final Map<Long, Long> nextAttemptAt = new ConcurrentHashMap<>();

    /** Счётчик последовательных неудачных попыток (для backoff). */
    private final Map<Long, Integer> attemptCount = new ConcurrentHashMap<>();

    /** Per-connection блокировки: connect к одному серверу не блокирует остальные. */
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    private static final long RETRY_BASE_MS = 5_000;
    private static final long RETRY_MAX_MS  = 60_000;

    // ------------------------------------------------------------------
    // Инициализация / завершение
    // ------------------------------------------------------------------

    /**
     * Инициализация подключений при старте приложения.
     * Ошибки отдельных подключений не прерывают запуск остальных (изоляция отказов):
     * упавшие попадут в цикл реконнекта с backoff.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Initializing OPC DA Connection Manager...");

        connectionRepository.findAll().stream()
                .filter(OpcDaConnection::getEnabled)
                .forEach(conn -> {
                    if (!tryConnect(conn)) {
                        log.warn("Startup connect failed for '{}' — will retry in background (backoff)",
                                conn.getName());
                    }
                });

        log.info("OPC DA Connection Manager initialized. Active connections: {}", clients.size());
    }

    /** Корректное завершение всех подключений при остановке приложения. */
    @PreDestroy
    public void shutdown() {
        log.info("Shutting down OPC DA Connection Manager...");
        clients.values().forEach(OpcDaClient::disconnect);
        clients.clear();
        nextAttemptAt.clear();
        attemptCount.clear();
        locks.clear();
    }

    // ------------------------------------------------------------------
    // Публичный API (используется UI / REST)
    // ------------------------------------------------------------------

    /**
     * Подключает сервер (если ещё не подключён). Потокобезопасно по id подключения.
     *
     * @return true, если подключение установлено (или уже было активно)
     */
    public boolean tryConnect(OpcDaConnection connection) {
        Long id = connection.getId();
        synchronized (lockFor(id)) {
            if (clients.containsKey(id)) {
                return true;
            }
            try {
                OpcDaClient client = new OpcDaClient(connection);
                client.connect();
                clients.put(id, client);
                clearBackoff(id);
                log.info("Successfully connected to OPC DA server: {}", connection.getName());
                return true;
            } catch (Exception e) {
                scheduleRetry(id);
                log.warn("Connect failed for '{}': {}", connection.getName(), e.getMessage());
                return false;
            }
        }
    }

    /** Отключает и удаляет подключение из пула. */
    public void disconnect(Long connectionId) {
        synchronized (lockFor(connectionId)) {
            OpcDaClient client = clients.remove(connectionId);
            if (client != null) {
                client.disconnect();
                clearBackoff(connectionId);
                log.info("Disconnected and removed OPC DA server: {}",
                        client.getConnectionConfig().getName());
            }
        }
    }

    /** Принудительное переподключение (UI / REST). Сбрасывает backoff. */
    public void reconnect(Long connectionId) {
        synchronized (lockFor(connectionId)) {
            OpcDaClient client = clients.get(connectionId);
            if (client != null) {
                try {
                    log.info("Forcing reconnect for: {}", client.getConnectionConfig().getName());
                    client.disconnect();
                    client.connect();
                    clearBackoff(connectionId);
                } catch (Exception e) {
                    client.markConnectionLost();          // освободить сессию
                    scheduleRetry(connectionId);
                    log.error("Failed to reconnect to OPC DA server: {} ({})",
                            client.getConnectionConfig().getName(), e.getMessage());
                }
            }
        }
    }

    public Optional<OpcDaClient> getClient(Long connectionId) {
        return Optional.ofNullable(clients.get(connectionId));
    }

    public Map<Long, OpcDaClient> getAllClients() {
        return Map.copyOf(clients);
    }

    // ------------------------------------------------------------------
    // Фоновый цикл: health-check + реконнект с backoff
    // ------------------------------------------------------------------

    /**
     * Фоновая задача. Интервал — app.opcda.reconnect-check-interval-ms.
     *
     * 1) Для живых подключений — активный health-check (Server.getServerState();
     *    при обрыве Utgard сам делает dispose, метод возвращает false).
     * 2) Для мёртвых и ещё не подключённых из БД — попытка восстановления,
     *    ограниченная экспоненциальным backoff.
     */
    @Scheduled(fixedDelayString = "${app.opcda.reconnect-check-interval-ms:5000}")
    public void checkAndReconnect() {

        // --- 1. Health-check и восстановление активных клиентов ---
        clients.forEach((id, client) -> {
            if (client.isConnected()) {
                if (client.checkAlive()) {
                    return; // живо
                }
                log.warn("Connection '{}' is dead (health check failed), will reconnect",
                        client.getConnectionConfig().getName());
            }
            attemptReconnect(id);
        });

        // --- 2. Подъём подключений из БД, отсутствующих в пуле ---
        connectionRepository.findAll().stream()
                .filter(OpcDaConnection::getEnabled)
                .filter(conn -> !clients.containsKey(conn.getId()))
                .forEach(conn -> {
                    if (!shouldTry(conn.getId())) {
                        return; // backoff ещё не истёк
                    }
                    if (tryConnect(conn)) {
                        log.info("Auto-reconnect successful for: {}", conn.getName());
                    } else {
                        log.debug("Still unreachable: {} (attempt #{})",
                                conn.getName(), attemptCount.get(conn.getId()));
                    }
                });
    }

    private void attemptReconnect(Long id) {
        if (!shouldTry(id)) {
            return;
        }
        synchronized (lockFor(id)) {
            OpcDaClient client = clients.get(id);
            String name = client != null ? client.getConnectionConfig().getName() : ("id=" + id);
            try {
                if (client != null) {
                    client.disconnect(); // очистка (безопасна, если уже чисто)
                }
                // перечитываем конфиг из БД — могли изменить через UI
                OpcDaConnection conn = connectionRepository.findById(id).orElse(null);
                if (conn == null || !Boolean.TRUE.equals(conn.getEnabled())) {
                    if (client != null) {
                        clients.remove(id);
                    }
                    return;
                }
                OpcDaClient fresh = new OpcDaClient(conn);
                fresh.connect();
                clients.put(id, fresh);
                clearBackoff(id);
                log.info("Auto-reconnect successful for: {}", name);
            } catch (Exception e) {
                if (client != null) {
                    client.markConnectionLost();
                }
                scheduleRetry(id);
                log.warn("Auto-reconnect failed for {}: {} (next attempt in {} ms)",
                        name, e.getMessage(),
                        Math.max(0, nextAttemptAt.getOrDefault(id, 0L) - System.currentTimeMillis()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Backoff
    // ------------------------------------------------------------------

    private boolean shouldTry(Long id) {
        return System.currentTimeMillis() >= nextAttemptAt.getOrDefault(id, 0L);
    }

    private void scheduleRetry(Long id) {
        int attempts = attemptCount.merge(id, 1, Integer::sum);
        // 5с → 10с → 20с → 40с → 60с (максимум)
        long backoff = Math.min(RETRY_MAX_MS, RETRY_BASE_MS << Math.min(attempts - 1, 10));
        nextAttemptAt.put(id, System.currentTimeMillis() + backoff);
    }

    private void clearBackoff(Long id) {
        nextAttemptAt.remove(id);
        attemptCount.remove(id);
    }

    private Object lockFor(Long id) {
        return locks.computeIfAbsent(id, k -> new Object());
    }
}