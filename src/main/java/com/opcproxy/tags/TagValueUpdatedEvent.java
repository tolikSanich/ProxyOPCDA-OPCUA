package com.opcproxy.tags;

import lombok.Getter;
import org.springframework.context.ApplicationEvent;
import java.time.Instant;

@Getter
public class TagValueUpdatedEvent extends ApplicationEvent {
    private final Long tagId;
    private final Object value;
    private final String quality;
    private final Instant sourceTimestamp; // Переименовано, чтобы не конфликтовать с ApplicationEvent.getTimestamp()

    public TagValueUpdatedEvent(Object source, Long tagId, Object value, String quality,
                                Instant sourceTimestamp) {
        super(source);
        this.tagId = tagId;
        this.value = value;
        this.quality = quality;
        this.sourceTimestamp = sourceTimestamp;
//        this.timestamp = sourceTimestamp; // Для совместимости, если нужно
    }

    // Явный геттер для timestamp, если он нужен в другом месте
    public Instant getEventTimestamp() {
        return sourceTimestamp;
    }
}