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
 *  - поддерживать подписку (Async20, при неудаче — SyncAccess), пересоздавая
 *    её при изменении набора тегов / периода (изменения через UI
 *    подхватываются без рестарта);
 *  - health-check канала (checkAlive) каждый цикл;
 *  - при смерти канала выйти — менеджер поднимет reconnect + новый поллер.
 *
 * Все блокирующие операции (DCOM-вызовы, sleep) безопасны на VT.
 */
@Slf4j
public class ConnectionPoller implements Runnable {

    /** Сколько циклов (по 1 с) между проверками, что подписка вообще существует. */
    private static final int RESUBSCRIBE_CHECK_DIVIDER = 5;
    private static final long CYCLE_MS = 1000;

    private final OpcDaClient client;
    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;

    private volatile boolean running = true;
    /** hash набора (itemId + period) активной подписки. 0 = ещё не подписаны. */
    private int subscribedHash = 0;

    public ConnectionPoller(OpcDaClient client,
                            TagRepository tagRepository,
                            TagRegistry tagRegistry) {
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
                    break;
                }

                List<Tag> tags = tagsOfThisConnection();
                int desiredHash = desiredHash(tags);
                boolean needResubscribe = desiredHash != subscribedHash;

                // Периодически проверяем, что подписка реально жива.
                // isSubscribed() == false после silent-failure Async20
                // (см. OpcDaClient.subscribeAsyncAll) или после markConnectionLost().
                boolean subscriptionMissing = (cycle % RESUBSCRIBE_CHECK_DIVIDER == 0)
                        && !tags.isEmpty()
                        && !client.isSubscribed();

                if (needResubscribe || subscriptionMissing) {
                    long period = periodOf(tags);
                    boolean ok = establishSubscription(tags, period, name);
                    if (ok) {
                        subscribedHash = desiredHash;
                    }
                    // иначе subscribedHash не трогаем — повторим на следующем цикле
                }

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

    // ------------------------------------------------------------------
    // Подписка: Async20 -> SyncAccess fallback
    // ------------------------------------------------------------------

    /**
     * Устанавливает подписку. Порядок:
     *   1. Если тегов нет — просто сбрасываем текущую подписку.
     *   2. Если клиент ещё считает Async20 поддерживаемым — пробуем Async20.
     *      При ЛЮБОЙ ошибке (в т.ч. silent failure внутри bind()) помечаем
     *      Async20 недоступным и переходим к п.3.
     *   3. Пробуем SyncAccess. Если и он падает — пробрасываем исключение,
     *      внешний catch вызовет handleConnectionFailure.
     *
     * @return true, если подписка успешно установлена (или тегов нет).
     */
    private boolean establishSubscription(List<Tag> tags, long period, String name)
            throws Exception {

        // --- Нет тегов: останавливаем любую текущую подписку ---
        if (tags.isEmpty()) {
            try {
                client.stopSubscription();
            } catch (Exception e) {
                log.debug("'{}': stopSubscription for empty tag set failed: {}",
                        name, e.getMessage());
            }
            log.debug("'{}': no tags to subscribe", name);
            return true;
        }

        // --- Пытаемся Async20 (если ещё не помечен как нерабочий) ---
        if (client.isAsyncSupported()) {
            try {
                log.debug("'{}': attempting Async20 subscription for {} tags", name, tags.size());
                client.subscribeAsyncAll(tags, period, this::onValue);
                log.info("'{}': Async20 subscription established ({} tags)", name, tags.size());
                return true;
            } catch (Exception e) {
                log.warn("'{}': Async20 subscription failed ({}), switching to SyncAccess: {}",
                        name, e.getClass().getSimpleName(), e.getMessage());
                client.setAsyncSupported(false);
                // НЕ пробрасываем — идём в SyncAccess
            }
        }

        // --- Fallback: SyncAccess ---
        try {
            int n = client.subscribeAll(tags, period, this::onValue);
            log.info("'{}': SyncAccess subscription established ({} of {} tags)",
                    name, n, tags.size());
            return true;
        } catch (Exception e) {
            log.warn("'{}': SyncAccess subscription failed: {}", name, e.getMessage());
            throw e;   // внешний catch -> handleConnectionFailure
        }
    }

    private void onValue(Long tagId, Object value, String quality, Instant ts) {
        tagRegistry.updateTagValue(tagId, value, quality, ts);
    }

    // ------------------------------------------------------------------
    // Хелперы
    // ------------------------------------------------------------------

    private List<Tag> tagsOfThisConnection() {
        Long connId = client.getConnectionId();
        return tagRepository.findBySourceType(SourceType.DA).stream()
                .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                .filter(t -> t.getConnection() != null
                        && connId.equals(t.getConnection().getId()))
                .filter(t -> t.getSourceItemId() != null
                        && !t.getSourceItemId().isBlank())
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
                .orElse(Long.valueOf(
                        client.getConnectionConfig().getDefaultRefreshPeriodMs()));
    }

    private int desiredHash(List<Tag> tags) {
        return Objects.hash(
                tags.stream().map(Tag::getId).sorted().collect(Collectors.toList()),
                periodOf(tags));
    }
}