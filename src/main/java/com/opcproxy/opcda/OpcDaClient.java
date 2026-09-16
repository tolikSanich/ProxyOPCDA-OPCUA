package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.enums.ReadMode;
import lombok.Getter;
import org.openscada.opc.dcom.da.OPCSERVERSTATUS;
import org.openscada.opc.lib.common.ConnectionInformation;
import org.openscada.opc.lib.da.Group;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.Server;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
@Getter
public class OpcDaClient {
    private static final Logger log = LoggerFactory.getLogger(OpcDaClient.class);

    private final OpcDaConnection connectionConfig;
    private Server server;
    private Group group;

    @Getter
    private final Map<String, Item> items = new ConcurrentHashMap<>();

    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicInteger errorCount = new AtomicInteger(0);
    private ReadMode currentReadMode;
    private volatile long lastDataTimestamp = System.currentTimeMillis();

    public OpcDaClient(OpcDaConnection connectionConfig) {
        this.connectionConfig = connectionConfig;
        this.currentReadMode = connectionConfig.getDefaultReadMode();
    }

    private static final java.util.regex.Pattern CLSID_PATTERN = java.util.regex.Pattern.compile(
            "^\\{?[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\}?$"
    );

    public synchronized void connect() throws Exception {
        if (connected.get()) {
            log.warn("Connection {} already connected", connectionConfig.getName());
            return;
        }
        log.info("Connecting to OPC DA server: {}@{}", connectionConfig.getName(), connectionConfig.getHost());

        String rawId = connectionConfig.getProgIdOrClsid() != null
                ? connectionConfig.getProgIdOrClsid().trim() : "";
        boolean clsidMode = rawId.matches(CLSID_PATTERN.pattern());

        try {
            ConnectionInformation connInfo = new ConnectionInformation();
            connInfo.setHost(connectionConfig.getHost());
            connInfo.setUser(connectionConfig.getUsername());
            connInfo.setPassword(connectionConfig.getPasswordEncrypted());
            // ВАЖНО: домен должен передаваться явно, иначе j-Interop не сможет
            // корректно построить NTLM-аутентификацию
            connInfo.setDomain(connectionConfig.getDomain() != null
                    ? connectionConfig.getDomain().trim() : "");

            if (clsidMode) {
                // Убираем фигурные скобки, Utgard сам их добавит
                String clsid = rawId;
                if (clsid.startsWith("{")) clsid = clsid.substring(1);
                if (clsid.endsWith("}")) clsid = clsid.substring(0, clsid.length() - 1);
                connInfo.setClsid(clsid.toUpperCase(java.util.Locale.ROOT));
                log.info("Using CLSID: {}", clsid);
            } else {
                connInfo.setProgId(rawId);
                log.info("Using ProgID: {} (требует доступа к реестру по SMB!)", rawId);
            }

            server = new Server(connInfo, null);
            server.connect();
            group = server.addGroup();

            connected.set(true);
            errorCount.set(0);
            log.info("Successfully connected to OPC DA server: {}", connectionConfig.getName());
        } catch (Exception e) {
            log.error("Failed to connect to OPC DA server: {}", connectionConfig.getName(), e);
            connected.set(false);
            throw e;
        }
    }
    /**
     * Проверка живости канала через штатный механизм Utgard.
     * Server.getServerState() при неудаче сам вызывает dispose() и возвращает null.
     */
    public boolean checkAlive() {
        if (server == null) {
            connected.set(false);
            return false;
        }
        OPCSERVERSTATUS status = server.getServerState(); // 2.5s timeout внутри
        if (status == null) {
            log.warn("Health check failed for {}, session disposed", connectionConfig.getName());
            connected.set(false);
            return false;
        }
        return true;
    }
    /**
     * Реакция на ошибку уровня соединения: помечаем канал мёртвым
     * и освобождаем сессию, чтобы checkAndReconnect поднял новую.
     */
    public synchronized void handleConnectionFailure(Throwable e) {
        String msg = String.valueOf(e.getMessage());
        boolean fatal = msg.contains("0x8001FFFF")      // j-Interop: внутренняя ошибка (мёртвый канал)
                || msg.contains("0x80010108")            // RPC_E_DISCONNECTED
                || msg.contains("0x800706BA")            // RPC_S_SERVER_UNAVAILABLE
                || msg.contains("Connection reset")
                || msg.contains("ping failed");
        if (fatal && connected.get()) {
            log.warn("Connection-level failure detected for {}: {} — marking lost, reconnect scheduled",
                    connectionConfig.getName(), msg);
            markConnectionLost();
        }
    }

    public synchronized void markConnectionLost() {
        connected.set(false);
        errorCount.incrementAndGet();
        try {
            if (server != null) {
                server.dispose();   // форкнет destroySession в отдельном потоке
            }
        } catch (Exception ex) {
            log.debug("Dispose after failure: {}", ex.getMessage());
        }
    }


    public synchronized void disconnect() {
        if (!connected.get()) return;
        log.info("Disconnecting from OPC DA server: {}", connectionConfig.getName());
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
            connected.set(false);
            log.info("Disconnected from OPC DA server: {}", connectionConfig.getName());
        } catch (Exception e) {
            log.info("Disconnect from {} completed with cleanup note: {}",
                    connectionConfig.getName(), e.getMessage());
        }
    }

    /**
     * Добавляет тег и возвращает его. Если тег уже есть, возвращает существующий.
     */
    public Item addItem(String itemId) throws Exception {
        if (!connected.get() || group == null) {
            throw new IllegalStateException("Not connected to OPC DA server");
        }
        if (items.containsKey(itemId)) {
            return items.get(itemId);
        }
        try {
            Item item = group.addItem(itemId);
            items.put(itemId, item);
            log.debug("Added item: {} to connection: {}", itemId, connectionConfig.getName());
            return item;
        } catch (Exception e) {
            // КРИТИЧЕСКОЕ ИСПРАВЛЕНИЕ: Обработка разрыва DCOM-сессии
            if (e.getMessage() != null && e.getMessage().contains("0x80010108")) {
                log.error("DCOM object disconnected (0x80010108) for {}. Marking connection as lost.", connectionConfig.getName());
                connected.set(false); // Это триггерит логику переподключения в OpcDaConnectionManager
            } else {
                log.error("Failed to add item: {} to connection: {}", itemId, connectionConfig.getName(), e);
            }
            throw e;
        }
    }

    public void removeItem(String itemId) {
        Item item = items.remove(itemId);
        if (item != null && group != null) {
            try {
                group.removeItem(itemId);
                log.debug("Removed item: {} from connection: {}", itemId, connectionConfig.getName());
            } catch (Exception e) {
                log.error("Failed to remove item: {} from connection: {}", itemId, connectionConfig.getName(), e);
            }
        }
    }

    public Map<String, Item> readAllSync() throws Exception {
        if (!connected.get() || group == null) {
            throw new IllegalStateException("Not connected to OPC DA server");
        }
        try {
            group.read(false);
            lastDataTimestamp = System.currentTimeMillis();
            errorCount.set(0);
            return items;
        } catch (Exception e) {
            errorCount.incrementAndGet();
            log.error("Sync read failed for connection: {}", connectionConfig.getName(), e);
            throw e;
        }
    }

    public boolean isConnected() {
        return connected.get();
    }

    public ConnectionState getState() {
        if (connected.get()) {
            return ConnectionState.CONNECTED;
        }
        return ConnectionState.DISCONNECTED;
    }

    public int getErrorCount() {
        return errorCount.get();
    }
}