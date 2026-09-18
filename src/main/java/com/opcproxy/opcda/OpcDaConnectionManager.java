package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.security.PasswordCipher;
import com.opcproxy.tags.TagRegistry;
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
 * Менеджер подключений. Жизненным циклом опроса теперь управляют
 * виртуальные потоки (ConnectionPoller на каждое подключение);
 * scheduled-задача осталась только как страховка реконнекта.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpcDaConnectionManager {

    private final OpcDaConnectionRepository connectionRepository;
    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;
    private final PasswordCipher passwordCipher;
    private final Map<Long, OpcDaClient> clients = new ConcurrentHashMap<>();
    private final Map<Long, ConnectionPoller> pollers = new ConcurrentHashMap<>();

    private final Map<Long, Long> nextAttemptAt = new ConcurrentHashMap<>();
    private final Map<Long, Integer> attemptCount = new ConcurrentHashMap<>();
    private final Map<Long, Object> locks = new ConcurrentHashMap<>();

    private static final long RETRY_BASE_MS = 5_000;
    private static final long RETRY_MAX_MS  = 60_000;

    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        log.info("Initializing OPC DA Connection Manager...");
        connectionRepository.findAll().stream()
                .filter(OpcDaConnection::getEnabled)
                .forEach(conn -> {
                    if (!tryConnect(conn)) {
                        log.warn("Startup connect failed for '{}' — background backoff retry",
                                conn.getName());
                    }
                });
        log.info("OPC DA Connection Manager initialized. Active connections: {}", clients.size());
    }

    @PreDestroy
    public void shutdown() {
        log.info("Shutting down OPC DA Connection Manager...");
        clients.keySet().forEach(this::stopPoller);
        clients.values().forEach(OpcDaClient::disconnect);
        clients.clear();
        nextAttemptAt.clear();
        attemptCount.clear();
        locks.clear();
    }

    // ------------------------------------------------------------------
    // API
    // ------------------------------------------------------------------

    public boolean tryConnect(OpcDaConnection connection) {
        Long id = connection.getId();
        synchronized (lockFor(id)) {
            if (clients.containsKey(id)) {
                return true;
            }
            try {
                OpcDaClient client = new OpcDaClient(connection, passwordCipher);
                client.connect();
                clients.put(id, client);
                clearBackoff(id);
                startPoller(id, client);
                log.info("Successfully connected to OPC DA server: {}", connection.getName());
                return true;
            } catch (Exception e) {
                scheduleRetry(id);
                log.warn("Connect failed for '{}': {}", connection.getName(), e.getMessage());
                return false;
            }
        }
    }

    public void disconnect(Long connectionId) {
        synchronized (lockFor(connectionId)) {
            stopPoller(connectionId);
            OpcDaClient client = clients.remove(connectionId);
            if (client != null) {
                client.disconnect();
                clearBackoff(connectionId);
                log.info("Disconnected and removed OPC DA server: {}",
                        client.getConnectionConfig().getName());
            }
        }
    }

    public void reconnect(Long connectionId) {
        synchronized (lockFor(connectionId)) {
            OpcDaClient client = clients.get(connectionId);
            if (client == null) return;
            try {
                log.info("Forcing reconnect for: {}", client.getConnectionConfig().getName());
                stopPoller(connectionId);
                client.disconnect();
                client.connect();
                clearBackoff(connectionId);
                startPoller(connectionId, client);
            } catch (Exception e) {
                client.markConnectionLost();
                scheduleRetry(connectionId);
                log.error("Failed to reconnect to {}: {}",
                        client.getConnectionConfig().getName(), e.getMessage());
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
    // Фоновая страховка реконнекта (поллеры сами держат подписки)
    // ------------------------------------------------------------------

    @Scheduled(fixedDelayString = "${app.opcda.reconnect-check-interval-ms:5000}")
    public void checkAndReconnect() {
        // 1. Мёртвые клиенты из пула
        clients.forEach((id, client) -> {
            if (client.isConnected() && client.checkAlive()) {
                return; // живо, поллер работает
            }
            attemptReconnect(id);
        });

        // 2. Не подключённые из БД
        connectionRepository.findAll().stream()
                .filter(OpcDaConnection::getEnabled)
                .filter(conn -> !clients.containsKey(conn.getId()))
                .forEach(conn -> {
                    if (!shouldTry(conn.getId())) return;
                    if (tryConnect(conn)) {
                        log.info("Auto-reconnect successful for: {}", conn.getName());
                    } else {
                        log.debug("Still unreachable: {} (attempt #{})",
                                conn.getName(), attemptCount.get(conn.getId()));
                    }
                });
    }

    private void attemptReconnect(Long id) {
        if (!shouldTry(id)) return;
        synchronized (lockFor(id)) {
            OpcDaClient client = clients.get(id);
            String name = client != null ? client.getConnectionConfig().getName() : ("id=" + id);
            try {
                stopPoller(id);
                if (client != null) client.disconnect();

                OpcDaConnection conn = connectionRepository.findById(id).orElse(null);
                if (conn == null || !Boolean.TRUE.equals(conn.getEnabled())) {
                    if (client != null) clients.remove(id);
                    return;
                }
                OpcDaClient fresh = new OpcDaClient(conn, passwordCipher);
                fresh.connect();
                clients.put(id, fresh);
                clearBackoff(id);
                startPoller(id, fresh);
                log.info("Auto-reconnect successful for: {}", name);
            } catch (Exception e) {
                if (client != null) client.markConnectionLost();
                scheduleRetry(id);
                log.warn("Auto-reconnect failed for {}: {} (next in {} ms)", name, e.getMessage(),
                        Math.max(0, nextAttemptAt.getOrDefault(id, 0L) - System.currentTimeMillis()));
            }
        }
    }

    // ------------------------------------------------------------------
    // Поллеры (виртуальные потоки)
    // ------------------------------------------------------------------

    private void startPoller(Long id, OpcDaClient client) {
        stopPoller(id);
        ConnectionPoller poller = new ConnectionPoller(client, tagRepository, tagRegistry);
        pollers.put(id, poller);
        Thread.ofVirtual().name("opc-poller-" + id + "-" + client.getConnectionConfig().getName())
                .start(poller);
    }

    private void stopPoller(Long id) {
        ConnectionPoller poller = pollers.remove(id);
        if (poller != null) {
            poller.stop();
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