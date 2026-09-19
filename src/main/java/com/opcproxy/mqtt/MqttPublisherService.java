package com.opcproxy.mqtt;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.tags.TagValueUpdatedEvent;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.paho.client.mqttv3.*;
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;

/**
 * MQTT-публикатор значений тегов (ТЗ §5.5).
 *
 * Топик:  {mqtt.base-topic}/tags/{tagName}
 * Payload: JSON {"tag":"...","value":...,"quality":"Good","timestamp":"ISO-8601"}
 *
 * Публикация событийная: TagRegistry.updateTagValue() шлёт TagValueUpdatedEvent
 * ТОЛЬКО при изменении значения/качества, поэтому дедупликация здесь не нужна.
 * Остаются фильтры:
 *  - тег должен иметь publishMqtt=true и быть в снапшоте конфигурации;
 *  - числовые значения глушатся в пределах mqttDeadband;
 *  - смена качества (в т.ч. Good<->Bad) публикуется ВСЕГДА.
 *
 * Переподключение: paho setAutomaticReconnect(true); конфигурация тегов
 * перечитывается каждые mqtt.config-refresh-ms.
 */
@Slf4j
@Service
public class MqttPublisherService implements MqttCallbackExtended {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;
    private record BufferedMessage(String topic, byte[] payload, int qos, boolean retained) {}
    private final MqttProperties props;
    private final TagRepository tagRepository;
    private final ArrayBlockingQueue<BufferedMessage> outbox;
    private final TagRegistry tagRegistry;
    private volatile boolean running = true;

    private MqttClient client;

    /** Снапшот настроек публикации: tagId -> TagConf. Обновляется по расписанию. */
    private record TagConf(String name, boolean publish, Double deadband, boolean enabled) {}
    private final Map<Long, TagConf> tagConfigs = new ConcurrentHashMap<>();

    /** Последнее опубликованное числовое значение (для deadband): tagId -> value. */
    private final Map<Long, Double> lastPublishedNumeric = new ConcurrentHashMap<>();

    public MqttPublisherService(MqttProperties props, TagRepository tagRepository, TagRegistry tagRegistry) {
        this.props = props;
        this.tagRepository = tagRepository;
        this.outbox = new java.util.concurrent.ArrayBlockingQueue<>(props.getBufferCapacity());
        this.tagRegistry = tagRegistry;
    }
    @PreDestroy
    private void initRunning(){
        running = false;
    }
    // ------------------------------------------------------------------
    // Жизненный цикл
    // ------------------------------------------------------------------

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!props.isEnabled()) {
            log.info("MQTT publisher disabled (mqtt.enabled=false)");
            return;
        }
        try {
            client = new MqttClient(props.getBroker(), props.getClientId(),
                    new MemoryPersistence());
            client.setCallback(this);

            MqttConnectOptions opts = new MqttConnectOptions();
            opts.setAutomaticReconnect(true);
            opts.setCleanSession(true);
            opts.setConnectionTimeout(10);
            if (props.getUsername() != null && !props.getUsername().isBlank()) {
                opts.setUserName(props.getUsername());
                opts.setPassword(props.getPassword() != null
                        ? props.getPassword().toCharArray() : new char[0]);
            }

            client.connect(opts);
            log.info("MQTT publisher connected to {} as '{}', base topic '{}'",
                    props.getBroker(), props.getClientId(), props.getBaseTopic());
        } catch (Exception e) {
            // Не роняем старт: брокер может появиться позже, авто-реконнект подхватит
            log.warn("MQTT broker {} unavailable ({}), automatic reconnect will retry",
                    props.getBroker(), e.getMessage());
        }
        refreshTagConfigs();
        if (props.getPublishIntervalMs() > 0) {
            long period = props.getPublishIntervalMs();
            Thread.ofVirtual().name("mqtt-heartbeat").start(() -> {
                while (running) {   // добавьте volatile boolean running = true; @PreDestroy -> false
                    try {
                        Thread.sleep(period);
                        heartbeat();
                    } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
                }
            });
            log.info("MQTT heartbeat mode: publish all every {} ms", period);
        }
    }
    /** Таймерный режим: публикуем все включённые теги независимо от изменений. */
    private void heartbeat() {
        if (!props.isEnabled() || client == null || !client.isConnected()) return;
        tagConfigs.forEach((tagId, conf) -> {
            if (!conf.publish() || !conf.enabled()) return;
            try {
                var tv = tagRegistry.getTagValue(tagId).orElse(null);
                if (tv == null) return;
                enqueue(new BufferedMessage(props.getBaseTopic() + "/tags/" + conf.name(),
                        buildJson(conf.name(), tv.getValue(), tv.getQuality())
                                .getBytes(StandardCharsets.UTF_8),
                        props.getQos(), props.isRetain()));
            } catch (Exception e) {
                log.debug("MQTT heartbeat failed for {}: {}", conf.name(), e.getMessage());
            }
        });
    }
    /** Обновление снапшота настроек тегов (какие публикуем, deadband'ы). */
    @Scheduled(fixedDelayString = "${app.mqtt.config-refresh-ms:5000}")
    public void refreshTagConfigs() {
        try {
            for (Tag t : tagRepository.findAll()) {
                // CALC-теги не публикуем, если они выключены (их значения stale)
                if (t.getSourceType() == SourceType.CALC && !Boolean.TRUE.equals(t.getEnabled())) {
                    continue;
                }
                tagConfigs.put(t.getId(), new TagConf(
                        t.getName(),
                        Boolean.TRUE.equals(t.getPublishMqtt()),
                        t.getMqttDeadband() != null ? t.getMqttDeadband().doubleValue() : null,
                        Boolean.TRUE.equals(t.getEnabled())));
            }
        } catch (Exception e) {
            log.debug("MQTT tag-config refresh failed: {}", e.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Публикация по событию (TagRegistry шлёт его только при изменении)
    // ------------------------------------------------------------------

    @EventListener
    public void onTagValueUpdated(TagValueUpdatedEvent event) {

        if (!props.isEnabled() || client == null || !client.isConnected()) {
            return;
        }
        try {
            Long tagId = event.getTagId();
            TagConf conf = tagConfigs.get(tagId);
            if (conf == null || !conf.publish() || !conf.enabled()) return;

            Object value = event.getValue();
            String quality = event.getQuality() != null ? event.getQuality() : "Bad";

            // --- Deadband для числовых значений (качество всегда публикуем) ---
            if (value instanceof Number num) {
                Double last = lastPublishedNumeric.get(tagId);
                if (last != null && conf.deadband() != null && conf.deadband() > 0
                        && Math.abs(num.doubleValue() - last) <= conf.deadband()) {
                    return;   // изменение в пределах deadband — глушим
                }
                lastPublishedNumeric.put(tagId, num.doubleValue());
            } else {
                // нечисловое/null значение (в т.ч. переход в Bad) — публикуем всегда
                lastPublishedNumeric.remove(tagId);
            }
            enqueue(new BufferedMessage(props.getBaseTopic() + "/tags/" + conf.name(),
                    buildJson(conf.name(), value, quality).getBytes(StandardCharsets.UTF_8),
                    props.getQos(), props.isRetain()));
//            publish(conf.name(), value, quality);

        } catch (Exception e) {
            log.debug("MQTT publish failed for tag {}: {}", event.getTagId(), e.getMessage());
        }
    }
    private void enqueue(BufferedMessage msg) throws Exception {
        if (client != null && client.isConnected()) {
            client.publish(msg.topic(), toMqtt(msg));
            return;
        }
        if (!outbox.offer(msg)) {          // очередь полна — discard oldest (§5.6.6)
            outbox.poll();
            outbox.offer(msg);
        }
    }
    private MqttMessage toMqtt(BufferedMessage m) {
        MqttMessage mm = new MqttMessage(m.payload());
        mm.setQos(m.qos());
        mm.setRetained(m.retained());
        return mm;
    }
    @Override
    public void connectComplete(boolean reconnect, String serverURI) {
        log.info("MQTT connected (reconnect={}) to {}", reconnect, serverURI);
        if (!reconnect) return;            // при первом старте буфер пуст
        int drained = 0;
        BufferedMessage msg;
        while ((msg = outbox.poll()) != null) {
            try {
                client.publish(msg.topic(), toMqtt(msg));
                drained++;
            } catch (Exception e) {
                log.warn("MQTT drain failed: {}", e.getMessage());
                break;
            }
        }
        if (drained > 0) log.info("MQTT outbox drained: {} messages after reconnect", drained);
    }


    /** Компактный JSON без внешних зависимостей (значение — числа/строки/null, инъекция безопасна). */
    private String buildJson(String tag, Object value, String quality) {
        String v = value == null ? "null"
                : value instanceof Number || value instanceof Boolean
                ? value.toString()
                : "\"" + value.toString()
                .replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        return "{\"tag\":\"" + tag + "\",\"value\":" + v
                + ",\"quality\":\"" + quality + "\""
                + ",\"timestamp\":\"" + TS_FMT.format(Instant.now()) + "\"}";
    }

    // ------------------------------------------------------------------
    // MqttCallback
    // ------------------------------------------------------------------

    @Override
    public void connectionLost(Throwable cause) {
        log.warn("MQTT connection lost: {} — automatic reconnect in progress",
                cause != null ? cause.getMessage() : "unknown");
    }

    @Override
    public void messageArrived(String topic, MqttMessage message) {
        // шлюз только публикует; подписок нет
    }

    @Override
    public void deliveryComplete(IMqttDeliveryToken token) {
        // no-op
    }
}