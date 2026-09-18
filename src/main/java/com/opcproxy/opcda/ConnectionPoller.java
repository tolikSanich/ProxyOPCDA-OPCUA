package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Петля жизненного цикла одного подключения на виртуальном потоке.
 *
 * Обязанности:
 *  - поддерживать подписку SyncAccess, пересоздавая её при изменении
 *    набора тегов / периода (изменения через UI подхватываются без рестарта);
 *  - health-check канала (checkAlive) каждый цикл;
 *  - при смерти канала выйти — менеджер поднимет reconnect + новый поллер.
 *
 * Все блокирующие операции (DCOM-вызовы, sleep) безопасны на VT.
 */
@Slf4j
public class ConnectionPoller implements Runnable {

    /** Сколько циклов (по 1 с) держим подписку до проверки изменений. */
    private static final int RESUBSCRIBE_CHECK_DIVIDER = 5;
    private static final long CYCLE_MS = 1000;

    private final OpcDaClient client;
    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;
    private boolean lastNoTags = true;

    private volatile boolean running = true;
    private int subscribedHash = 0;   // hash набора (itemId + period) активной подписки

    public ConnectionPoller(OpcDaClient client, TagRepository tagRepository, TagRegistry tagRegistry) {
        this.client = client;
        this.tagRepository = tagRepository;
        this.tagRegistry = tagRegistry;
    }

    public void stop() {
        running = false;
    }

    @Override
    public void run() {
        String name = client.getConnectionConfig().getName();
        log.info("Poller (virtual) started for '{}'", name);

        int cycle = 0;
        while (running) {
            try {
                if (!client.isConnected()) {
                    log.info("'{}': connection lost, poller exits (manager will reconnect)", name);
                    break;
                }

                List<Tag> tags = tagsOfThisConnection();

                // Пересоздание подписки при изменении набора тегов/периода
                int desiredHash = desiredHash(tags);
                boolean needResubscribe = desiredHash != subscribedHash;
                if (needResubscribe || (cycle % RESUBSCRIBE_CHECK_DIVIDER == 0 && !client.isSubscribed())) {
                    long period = periodOf(tags);
                    int n = client.subscribeAll(tags, period, this::onValue);
                    subscribedHash = needResubscribe ? desiredHash : subscribedHash;
                    boolean noTags = tags.isEmpty();
                    if (noTags != lastNoTags) {           // поле private boolean lastNoTags = true;
                        log.info("'{}': {}", name, noTags ? "no tags configured yet" : "tags found, subscribing");
                        lastNoTags = noTags;
                    }
                }

                // Health-check: смерть канала -> выход, менеджер поднимет заново
                if (!client.checkAlive()) {
                    log.warn("'{}': health check failed, poller exits", name);
                    break;
                }

            } catch (Exception e) {
                client.handleConnectionFailure(e);
                log.debug("'{}': poll cycle error: {}", name, e.getMessage());
            }

            cycle++;
            try {
                Thread.sleep(CYCLE_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        client.stopSubscription();
        log.info("Poller (virtual) stopped for '{}'", name);
    }

    private void onValue(Long tagId, Object value, String quality, Instant ts) {
        tagRegistry.updateTagValue(tagId, value, quality, ts);
    }

    private List<Tag> tagsOfThisConnection() {
        Long connId = client.getConnectionId();
        return tagRepository.findBySourceType(SourceType.DA).stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .filter(t -> t.getConnection() != null && connId.equals(t.getConnection().getId()))
                .filter(t -> t.getSourceItemId() != null && !t.getSourceItemId().isBlank())
                .collect(Collectors.toList());
    }

    private long periodOf(List<Tag> tags) {
        return tags.isEmpty()
                ? client.getConnectionConfig().getDefaultRefreshPeriodMs()
                : tags.stream()
                .map(Tag::getRefreshPeriodMs)
                .filter(Objects::nonNull)
                .min(Integer::compare)     // самый быстрый тег задаёт период группы
                .map(Integer::longValue)
                .orElse(Long.valueOf(client.getConnectionConfig().getDefaultRefreshPeriodMs()));
    }

    private int desiredHash(List<Tag> tags) {
        return Objects.hash(
                tags.stream().map(Tag::getId).sorted().collect(Collectors.toList()),
                periodOf(tags));
    }
}