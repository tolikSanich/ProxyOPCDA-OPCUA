package com.opcproxy.calc;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Сервис расчёта CALC-тегов (ТЗ §5.2.4 — режим SYNC по расписанию).
 *
 * Один виртуальный поток пересчитывает все CALC-теги по их refreshPeriodMs.
 * Качество результата: Good — если ВСЕ входы Good; иначе Bad_DependencyBad (§5.4.6).
 * Результат публикуется через TagRegistry -> событие -> UI + OPC UA автоматически.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalcEngineService {

    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;
    private final SpelCalcEngine engine;
    private final CalcDependencyValidator validator;

    private final AtomicBoolean running = new AtomicBoolean(true);

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        Thread.ofVirtual().name("calc-engine").start(this::loop);
        log.info("CalcEngine started (virtual thread)");
    }

    @PreDestroy
    public void stop() {
        running.set(false);
    }

    private void loop() {
        while (running.get()) {
            long nextDue = Long.MAX_VALUE;
            try {
                List<Tag> calcTags = tagRepository.findAll().stream()
                        .filter(t -> t.getSourceType() == SourceType.CALC)
                        .filter(t -> Boolean.TRUE.equals(t.getEnabled()))
                        .filter(t -> t.getExpression() != null && !t.getExpression().isBlank())
                        .toList();

                long now = System.currentTimeMillis();
                for (Tag tag : calcTags) {
                    long period = tag.getRefreshPeriodMs() != null ? tag.getRefreshPeriodMs() : 1000L;
                    if (isDue(tag, period, now)) {
                        recalculate(tag);
                        markCalculated(tag.getId(), now);
                    }
                    long due = lastCalcOrDefault(tag.getId()) + period;
                    nextDue = Math.min(nextDue, due);
                }
            } catch (Exception e) {
                log.warn("CalcEngine cycle error: {}", e.getMessage());
            }

            long sleep = Math.min(200, Math.max(10, nextDue - System.currentTimeMillis()));
            try {
                Thread.sleep(sleep);   // виртуальный поток — сон бесплатен
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        log.info("CalcEngine stopped");
    }

    // --- учёт времени расчёта (id -> timestamp) ---
    private final Map<Long, Long> lastCalc = new java.util.concurrent.ConcurrentHashMap<>();

    private boolean isDue(Tag tag, long period, long now) {
        return now >= lastCalcOrDefault(tag.getId()) + period;
    }

    private long lastCalcOrDefault(Long id) {
        return lastCalc.getOrDefault(id, 0L);
    }

    private void markCalculated(Long id, long ts) {
        lastCalc.put(id, ts);
    }

    // ------------------------------------------------------------------

    private void recalculate(Tag tag) {
        try {
            List<String> refs = validator.extractRefs(tag.getExpression());
            Map<String, Object> inputValues = new HashMap<>();
            int worst = 0;                       // 0=Good, 1=Uncertain, 2=Bad (иерархия §5.4.7)

            for (String ref : refs) {
                var tv = tagRegistry.getTagValueByName(ref).orElse(null);
                if (tv == null || tv.getValue() == null
                        || tv.getQuality() == null || tv.getQuality().startsWith("Bad")) {
                    worst = 2;
                    break;                       // Bad среди входов — дальше не считаем
                }
                if (tv.getQuality().startsWith("Uncertain")) {
                    worst = Math.max(worst, 1);  // Uncertain учитываем, но значение используем
                }
                inputValues.put(ref, tv.getValue());
            }

            if (worst == 2) {
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_DependencyBad", Instant.now());
                return;
            }

            Object raw = engine.evaluate(tag.getId(), tag.getExpression(), inputValues);
            Object result = coerce(raw, tag.getDataType());
            if (result == null) {
                tagRegistry.updateTagValue(tag.getId(), null, "Bad_TypeMismatch", Instant.now());
                return;
            }
            // §5.4.7: Uncertain без Bad -> Uncertain (значение считается по имеющимся данным)
            String quality = worst == 1 ? "Uncertain_DependencyUncertain" : "Good";
            tagRegistry.updateTagValue(tag.getId(), result, quality, Instant.now());

        } catch (IllegalArgumentException e) {
            log.debug("Calc '{}' config error: {}", tag.getName(), e.getMessage());
            tagRegistry.updateTagValue(tag.getId(), null, "Bad_Configuration", Instant.now());
        } catch (Exception e) {
            log.debug("Calc '{}' failed: {}", tag.getName(), e.getMessage());
            tagRegistry.updateTagValue(tag.getId(), null, "Bad_CalculationError", Instant.now());
        }
    }

    /** Приведение результата SpEL к типу тега. null => несоответствие. */
    private Object coerce(Object raw, DataType type) {
        if (raw == null) return null;
        try {
            return switch (type) {
                case BOOLEAN -> raw instanceof Boolean b ? b : Boolean.parseBoolean(raw.toString());
                case DOUBLE  -> ((Number) raw).doubleValue();
                case FLOAT   -> ((Number) raw).floatValue();
                case INT32   -> raw instanceof Number n ? n.intValue()
                        : (int) Double.parseDouble(raw.toString());
                case UINT32, INT16, UINT16 -> ((Number) raw).intValue();
                case BYTE, SBYTE -> ((Number) raw).byteValue();
                case STRING  -> raw.toString();
                case DATETIME -> raw instanceof Instant i ? i : Instant.parse(raw.toString());
                default -> raw;
            };
        } catch (Exception e) {
            return null;
        }
    }
}