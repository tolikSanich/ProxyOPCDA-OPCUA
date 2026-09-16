package com.opcproxy.tags;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import org.slf4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class TagRegistry {

    private static final Logger log = LoggerFactory.getLogger(TagRegistry.class);
    private final ApplicationEventPublisher eventPublisher;
    private final TagRepository tagRepository;
    private final Map<Long, TagValue> tagValues = new ConcurrentHashMap<>();

    public TagRegistry(ApplicationEventPublisher eventPublisher, TagRepository tagRepository) {
        this.eventPublisher = eventPublisher;
        this.tagRepository = tagRepository;
    }

    private volatile boolean initialized = false;

    @EventListener(ApplicationReadyEvent.class)
    public void init() {
        if (initialized) return;
        initialized = true;
        log.info("Initializing Tag Registry");

        // Загружаем все активные теги из БД
        tagRepository.findAllEnabled().forEach(tag -> {
            tagValues.put(tag.getId(), new TagValue(tag.getName()));
        });

        log.info("Tag Registry initialized with {} tags", tagValues.size());
    }

    // В методе updateTagValue:
    public void updateTagValue(Long tagId, Object value, String quality, Instant timestamp) {
        TagValue tagValue = tagValues.get(tagId);
        if (tagValue != null) {
            boolean changed = !Objects.equals(tagValue.getValue(), value)
                    || !Objects.equals(tagValue.getQuality(), quality);
            tagValue.setValue(value);
            tagValue.setQuality(quality);
            tagValue.setTimestamp(timestamp);

            if (changed) {
                eventPublisher.publishEvent(new TagValueUpdatedEvent(this, tagId, value, quality, timestamp));
            }
        }
    }

    public Optional<TagValue> getTagValue(Long tagId) {
        return Optional.ofNullable(tagValues.get(tagId));
    }

    public Optional<TagValue> getTagValueByName(String tagName) {
        return tagValues.values().stream()
                .filter(tv -> tv.getName().equals(tagName))
                .findFirst();
    }

    public Map<Long, TagValue> getAllTagValues() {
        return Map.copyOf(tagValues);
    }

    public void registerTag(Tag tag) {
        tagValues.put(tag.getId(), new TagValue(tag.getName()));
        log.debug("Registered tag: {}", tag.getName());
    }

    public void unregisterTag(Long tagId) {
        tagValues.remove(tagId);
        log.debug("Unregistered tag: {}", tagId);
    }

    public static class TagValue {
        private final String name;
        private volatile Object value;
        private volatile String quality = "Bad";
        private volatile Instant timestamp = Instant.now();

        public TagValue(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public Object getValue() { return value; }
        public void setValue(Object value) { this.value = value; }
        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }
        public Instant getTimestamp() { return timestamp; }
        public void setTimestamp(Instant timestamp) { this.timestamp = timestamp; }
    }
}