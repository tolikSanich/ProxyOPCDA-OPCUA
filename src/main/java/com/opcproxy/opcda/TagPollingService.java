package com.opcproxy.opcda;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jinterop.dcom.core.JIUnsignedByte;
import org.jinterop.dcom.core.JIUnsignedInteger;
import org.jinterop.dcom.core.JIUnsignedShort;
import org.jinterop.dcom.core.JIVariant;
import org.openscada.opc.lib.da.Item;
import org.openscada.opc.lib.da.ItemState;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.reflect.Method;
import java.time.Instant;
import java.util.Calendar;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class TagPollingService {

    private final OpcDaConnectionManager connectionManager;
    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;

    @Scheduled(fixedDelayString = "${app.opcda.polling-interval-ms:1000}")
    public void pollTags() {
        List<Tag> daTags = tagRepository.findBySourceType(SourceType.DA);

        for (Tag tag : daTags) {
            if (!tag.getEnabled() || tag.getConnection() == null) continue;

            Long connId = tag.getConnection().getId();
            Optional<com.opcproxy.opcda.OpcDaClient> clientOpt = connectionManager.getClient(connId);

            if (clientOpt.isEmpty() || !clientOpt.get().isConnected()) {
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_NotConnected", Instant.now());
                continue;
            }

            com.opcproxy.opcda.OpcDaClient client = clientOpt.get();
            String itemId = tag.getSourceItemId();

            if (itemId == null || itemId.isBlank()) {
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_Configuration", Instant.now());
                continue;
            }

            try {
                Item item = client.addItem(itemId);
                ItemState state = item.read(false);

                // 1. Распаковка JIVariant / JIUnsigned* (исправление №8, Da Client §4.3)
                Object rawValue = state.getValue();
                Object cleanValue = unwrapVariant(rawValue);

                // 2. Качество OPC DA: 192 = Good, 64 = Bad, 80 = Uncertain
                String quality = parseOpcQuality(state.getQuality());

                // 3. Значение при качестве != Good — null (исправление №15)
                if (!quality.startsWith("Good")) {
                    cleanValue = null;
                }

                Calendar calendar = state.getTimestamp();
                Instant timestamp = calendar != null ? calendar.toInstant() : Instant.now();

                tagRegistry.updateTagValue(tag.getId(), cleanValue, quality, timestamp);

            } catch (Exception e) {
                // Ошибки уровня соединения помечают канал мёртвым → реконнект через ≤5 сек
                client.handleConnectionFailure(e);
                log.debug("Failed to read tag {} ({}): {}", tag.getName(), itemId, e.getMessage());
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_CommunicationFailure", Instant.now());
            }
        }
    }

    /** Распаковка JIVariant и беззнаковых типов j-Interop в стандартные Java-типы. */
    private Object unwrapVariant(Object raw) {
        if (raw == null) return null;

        if (raw instanceof JIVariant variant) {
            try {
                return unwrapVariant(variant.getObject());
            } catch (Exception e) {
                return raw;
            }
        }
        if (raw instanceof JIUnsignedByte b) return b.getValue();    // 0..255
        if (raw instanceof JIUnsignedShort s) return s.getValue();   // 0..65535
        if (raw instanceof JIUnsignedInteger i) return i.getValue(); // 0..4294967295

        // Fallback для прочих JIUnsigned* через рефлексию
        if (raw.getClass().getName().startsWith("org.jinterop.dcom.core.JIUnsigned")) {
            try {
                Method getValue = raw.getClass().getMethod("getValue");
                return getValue.invoke(raw);
            } catch (Exception ignore) { }
        }
        return raw;
    }

    /** Старшие 2 бита кода качества OPC DA определяют статус. */
    private String parseOpcQuality(int q) {
        int status = q & 0xC0;
        if (status == 0xC0) return "Good";
        if (status == 0x40) return "Bad";
        if (status == 0x80) return "Uncertain";
        return "Bad_Unknown";
    }
}