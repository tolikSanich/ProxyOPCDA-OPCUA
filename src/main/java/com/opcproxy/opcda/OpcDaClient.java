package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.rest.dto.RestDtos;
import com.opcproxy.security.PasswordCipher;
import com.opcproxy.tags.TagRegistry;
import lombok.Getter;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.da.AccessBase;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.openscada.opc.lib.da.Server;
import org.openscada.opc.lib.da.SyncAccess;
import org.openscada.opc.lib.da.browser.FlatBrowser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Клиент одного OPC DA-подключения.
 * <p>
 * Два режима работы:
 * 1) Подписка (основной, ТЗ §5.2.3): subscribeAll() создает SyncAccess —
 * Utgard сам опрашивает группу с периодом подключения и дергает callback.
 * 2) Поштучный sync-режим (резерв, ТЗ §5.2.4): addItem() + readAllSync().
 * <p>
 * Scheduler создается на виртуальных потоках (нужен Utgard'у для AccessBase
 * и async- to механизмов). Патчи Server/JIComServer (session security) применяются
 * внутри патченных классов из src/main/java.
 */
@Getter
public class OpcDaClient {
    private static final Logger log = LoggerFactory.getLogger(OpcDaClient.class);

    private static final Set<Class<?>> UA_SAFE = Set.of(
            Boolean.class, Byte.class, Short.class, Integer.class, Long.class,
            Float.class, Double.class, String.class,
            org.eclipse.milo.opcua.stack.core.types.builtin.DateTime.class);

    private static final Pattern CLSID_PATTERN = Pattern.compile(
            "^\\{?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}?$");

    /**
     * ItemID, которые не удалось подписать (ТЗ §5.2.8); перепроверяются по расписанию.
     */
    private final Set<String> notFoundItemIds = ConcurrentHashMap.newKeySet();

    private final OpcDaConnection connectionConfig;
    private final Long connectionId;
    private final PasswordCipher passwordCipher;
    private final TagRegistry tagRegistry;

    private Server server;
    private Group group;
    private ScheduledExecutorService scheduler;
    private volatile AccessBase access;
    private final Map<String, Item> items = new ConcurrentHashMap<>();
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private volatile long lastDataTimestamp = System.currentTimeMillis();

    @FunctionalInterface
    public interface TagValueSink {
        void accept(Long tagId, Object value, String quality, Instant timestamp);
    }

    public OpcDaClient(OpcDaConnection connectionConfig, PasswordCipher passwordCipher, TagRegistry tagRegistry) {
        this.connectionConfig = connectionConfig;
        this.connectionId = connectionConfig.getId();
        this.passwordCipher = passwordCipher;
        this.tagRegistry = tagRegistry;
    }

    // ------------------------------------------------------------------
    // Подключение
    // ------------------------------------------------------------------
    public synchronized void connect() throws Exception {
        if (connected.get()) {
            log.warn("Connection {} already connected", connectionConfig.getName());
            return;
        }
        log.info("Connecting to OPC DA server: {}@{}", connectionConfig.getName(), connectionConfig.getHost());

        String rawId = connectionConfig.getProgIdOrClsid() != null ? connectionConfig.getProgIdOrClsid().trim() : "";
        boolean clsidMode = rawId.matches(CLSID_PATTERN.pattern());

        try {
            ConnectionInformation connInfo = new ConnectionInformation();
            connInfo.setHost(connectionConfig.getHost());
            connInfo.setUser(connectionConfig.getUsername());
            connInfo.setPassword(passwordCipher.decrypt(connectionConfig.getPasswordEncrypted()));
            connInfo.setDomain(connectionConfig.getDomain() != null ? connectionConfig.getDomain().trim() : "");

            if (clsidMode) {
                String clsid = rawId;
                if (clsid.startsWith("{")) clsid = clsid.substring(1);
                if (clsid.endsWith("}")) clsid = clsid.substring(0, clsid.length() - 1);
                connInfo.setClsid(clsid.toUpperCase(Locale.ROOT));
                log.info("Using CLSID: {}", clsid);
            } else {
                connInfo.setProgId(rawId);
                log.info("Using ProgID: {} (требует доступа к реестру по SMB)", rawId);
            }

            this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = Thread.ofVirtual()
                        .name("utgard-sched-" + connectionConfig.getName())
                        .unstarted(r);
                return t;
            });

            server = new Server(connInfo, this.scheduler);
            server.connect();
            group = server.addGroup();
            connected.set(true);
            errorCount.set(0);
            log.info("Successfully connected to OPC DA server: {}", connectionConfig.getName());
        } catch (Exception e) {
            cleanupScheduler();
            connected.set(false);
            log.error("Failed to connect to OPC DA server: {}", connectionConfig.getName(), e);
            throw e;
        }
    }

    public synchronized void disconnect() {
        if (!connected.get() && server == null) return;
        log.info("Disconnecting from OPC DA server: {}", connectionConfig.getName());
        stopSubscription();
        try {
            if (group != null) {
                group.clear();
                group = null;
            }
            if (server != null) {
                server.disconnect();
                server = null;
            }
            items.clear();
            notFoundItemIds.clear();
            log.info("Disconnected from OPC DA server: {}", connectionConfig.getName());
        } catch (Exception e) {
            log.info("Disconnect from {} completed with cleanup note: {}", connectionConfig.getName(), e.getMessage());
        } finally {
            cleanupScheduler();
            connected.set(false);
        }
    }

    // ------------------------------------------------------------------
    // Браузинг (ТЗ §5.2.6)
    // ------------------------------------------------------------------
    /**
     * Получение иерархического списка тегов с OPC DA-сервера.
     * ТЗ §5.2.6: Если сервер не поддерживает иерархический просмотр,
     * система должна автоматически переходить к плоскому списку.
     *
     * РЕАЛИзация: Мы используем FlatBrowser (поддерживается 100% серверов),
     * получаем плоский список ItemID и строим из него дерево на стороне Java,
     * разбивая строки по разделителям '/' или '\'. Это обходит проблему
     * protected-методов в TreeBrowser библиотеки Utgard 1.5.0.
     *
     * @return иерархический список элементов с name, itemId и признаком isBranch.
     * @throws Exception если не подключено или браузер не поддерживается.
     */
    public List<RestDtos.BrowseNodeDto> browseTree() throws Exception {
        if (!connected.get() || server == null) {
            throw new IllegalStateException("Not connected: " + connectionConfig.getName());
        }

        FlatBrowser flatBrowser = server.getFlatBrowser();
        if (flatBrowser == null) {
            throw new UnsupportedOperationException("Flat browsing not supported by this server");
        }

        Collection<String> flatItems = flatBrowser.browse();

        Map<String, RestDtos.BrowseNodeDto> nodeMap = new LinkedHashMap<>();
        List<RestDtos.BrowseNodeDto> rootNodes = new ArrayList<>();

        for (String itemId : flatItems) {
            if (itemId == null || itemId.isBlank()) continue;

            // Разделяем ItemID на части (поддерживаем '/' и '\')
            String[] parts = itemId.split("[\\\\/]");
            String currentPath = "";
            RestDtos.BrowseNodeDto parent = null;

            for (int i = 0; i < parts.length; i++) {
                String part = parts[i];
                currentPath = currentPath.isEmpty() ? part : currentPath + "/" + part;
                boolean isLeaf = (i == parts.length - 1);

                if (!nodeMap.containsKey(currentPath)) {
                    RestDtos.BrowseNodeDto node = new RestDtos.BrowseNodeDto(
                            part,
                            isLeaf ? itemId : null, // itemId есть только у листа (конкретного тега)
                            !isLeaf,                // isBranch = true для всех, кроме последнего элемента
                            new ArrayList<>()
                    );
                    nodeMap.put(currentPath, node);

                    if (parent == null) {
                        rootNodes.add(node);
                    } else {
                        parent.children().add(node);
                    }
                }
                parent = nodeMap.get(currentPath);
            }
        }

        return rootNodes;
    }

    // ------------------------------------------------------------------
    // Подписка (основной режим)
    // ------------------------------------------------------------------
    /**
     * Создает/пересоздает подписку SyncAccess на набор тегов подключения.
     * Utgard сам опрашивает группу с заданным периодом и вызывает sink на каждое чтение.
     *
     * ТЗ §5.2.3, §5.2.8: Если конкретный ItemID не найден, подписка на остальные
     * продолжается, а проблемный ItemID заносится в notFoundItemIds для последующего речека.
     *
     * @param tags     теги ЭТОГО подключения (enabled, с непустым sourceItemId)
     * @param periodMs период опроса группы, мс
     * @param sink     приемник значений
     * @return число реально подписанных тегов
     * @throws Exception если клиент не подключен
     */
    public synchronized int subscribeAll(Collection<Tag> tags, long periodMs, TagValueSink sink) throws Exception {
        // 1. Останавливаем предыдущую подписку, если она была
        stopSubscription();

        if (!connected.get() || server == null) {
            throw new IllegalStateException("Not connected: " + connectionConfig.getName());
        }

        if (tags == null || tags.isEmpty()) {
            return 0;
        }

        log.info("Subscribing {} tags of '{}' with period {} ms", tags.size(), connectionConfig.getName(), periodMs);

        // 2. Создаем объект синхронного доступа Utgard
        SyncAccess syncAccess = new SyncAccess(server, (int) periodMs);
        int subscribed = 0;

        // 3. Пытаемся добавить каждый тег. Ошибка на одном теге не прерывает цикл.
        for (Tag tag : tags) {
            String itemId = tag.getSourceItemId();
            try {
                final Long tagId = tag.getId();
                // Регистрируем callback для конкретного ItemID
                syncAccess.addItem(itemId, (item, state) -> deliverValue(tagId, state, sink));

                // Успех: убираем из списка проблемных, если он там был
                notFoundItemIds.remove(itemId);
                subscribed++;
            } catch (Exception e) {
                // Неудача: логируем, добавляем в список проблемных, уведомляем sink
                log.debug("Subscribe failed for item '{}' (tag '{}'): {}", itemId, tag.getName(), e.getMessage());
                notFoundItemIds.add(itemId);
                sink.accept(tag.getId(), null, "Bad_NotFound", Instant.now());
            }
        }

        // 4. Активируем подписку (начинает работу фоновый планировщик Utgard)
        syncAccess.bind();
        this.access = syncAccess;

        // 5. Итоговое логиров状态
        log.info("Subscribed {} tags of '{}'", subscribed, connectionConfig.getName());
        if (!notFoundItemIds.isEmpty()) {
            log.warn("{} item(s) still not found on '{}'", notFoundItemIds.size(), connectionConfig.getName());
        }

        return subscribed;
    }

        public synchronized void stopSubscription() {
            AccessBase a = this.access;
            this.access = null;
            if (a != null) {
                try {
                    a.unbind();
                } catch (Exception e) {
                    log.debug("Unbind failed for {}: {}", connectionConfig.getName(), e.getMessage());
                }
            }
        }

        public boolean isSubscribed() {
            return access != null;
        }

        private void deliverValue(Long tagId, ItemState state, TagValueSink sink) {
            try {
                Object value = OpcDaValues.unwrapVariant(state.getValue());
                String quality = OpcDaValues.parseOpcQuality(state.getQuality());

                if (!quality.startsWith("Good")) {
                    value = null;
                } else if (value != null && !UA_SAFE.contains(value.getClass())) {
                    log.debug("Coercing unsupported value type {} for tag {}", value.getClass().getName(), tagId);
                    value = String.valueOf(value);
                }

                Calendar ts = state.getTimestamp();
                sink.accept(tagId, value, quality, ts != null ? ts.toInstant() : Instant.now());
                lastDataTimestamp = System.currentTimeMillis();
            } catch (Exception e) {
                log.debug("Deliver value failed for tag {}: {}", tagId, e.getMessage());
                sink.accept(tagId, null, "Bad_CommunicationFailure", Instant.now());
            }
        }

        // ------------------------------------------------------------------
        // Поштучный sync-режим (резерв / тестирование)
        // ------------------------------------------------------------------
        public Item addItem(String itemId) throws Exception {
            if (!connected.get() || group == null) {
                throw new IllegalStateException("Not connected to OPC DA server");
            }
            Item existing = items.get(itemId);
            if (existing != null) return existing;
            try {
                Item item = group.addItem(itemId);
                items.put(itemId, item);
                return item;
            } catch (Exception e) {
                handleConnectionFailure(e);
                notFoundItemIds.add(itemId);
                log.debug("addItem failed for '{}': {}", itemId, e.getMessage());
                throw e;
            }
        }

        public void removeItem(String itemId) {
            Item item = items.remove(itemId);
            if (item != null && group != null) {
                try {
                    group.removeItem(itemId);
                } catch (Exception e) {
                    log.debug("removeItem {} failed: {}", itemId, e.getMessage());
                }
            }
        }

        public ItemState readSync(String itemId) throws Exception {
            Item item = addItem(itemId);
            ItemState state = item.read(false);
            lastDataTimestamp = System.currentTimeMillis();
            return state;
        }

        // ------------------------------------------------------------------
        // Health / отказоустойчивость
        // ------------------------------------------------------------------
        public boolean checkAlive() {
            if (server == null) {
                connected.set(false);
                return false;
            }
            try {
                if (server.getServerState() == null) {
                    log.warn("Health check failed for {}, session disposed", connectionConfig.getName());
                    connected.set(false);
                    return false;
                }
                return true;
            } catch (Throwable t) {
                log.warn("Health check threw for {}: {}", connectionConfig.getName(), t.getMessage());
                connected.set(false);
                return false;
            }
        }

        public synchronized void handleConnectionFailure(Throwable e) {
            String msg = String.valueOf(e.getMessage());
            boolean fatal = msg.contains("0x8001FFFF")
                    || msg.contains("0x80010108")
                    || msg.contains("0x800706BA")
                    || msg.contains("0x800703FA")
                    || msg.contains("0x800700A4")
                    || msg.contains("Connection reset")
                    || msg.contains("ping failed");

            if (fatal && connected.get()) {
                log.warn("Connection-level failure for {}: {} — marking lost", connectionConfig.getName(), msg);
                markConnectionLost();
            }
        }

        public synchronized void markConnectionLost() {
            connected.set(false);
            errorCount.incrementAndGet();
            stopSubscription();
            try {
                if (server != null) {
                    server.dispose();
                }
            } catch (Exception ex) {
                log.debug("Dispose after failure: {}", ex.getMessage());
            }
        }

        public boolean isConnected() {
            return connected.get();
        }

        public ConnectionState getState() {
            return connected.get() ? ConnectionState.CONNECTED : ConnectionState.DISCONNECTED;
        }

        public int getErrorCount() {
            return errorCount.get();
        }

        public boolean hasNotFoundItems() {
            return !notFoundItemIds.isEmpty();
        }

        public int getNotFoundCount() {
            return notFoundItemIds.size();
        }

        // ------------------------------------------------------------------
        // Внутреннее
        // ------------------------------------------------------------------
        private void cleanupScheduler() {
            if (scheduler != null) {
                scheduler.shutdownNow();
                scheduler = null;
            }
        }
    }