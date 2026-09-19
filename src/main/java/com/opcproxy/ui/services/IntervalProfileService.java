package com.opcproxy.ui.services;

import com.opcproxy.persistence.entity.IntervalProfile;
import com.opcproxy.persistence.repository.IntervalProfileRepository;
import com.opcproxy.persistence.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Управление профилями интервалов OPC UA (ТЗ §5.3).
 * При старте создаёт три системных профиля, если их нет.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class IntervalProfileService {

    private final IntervalProfileRepository repository;
    private final TagRepository tagRepository;

    @EventListener(ApplicationReadyEvent.class)   // PostConstruct может отработать раньше Flyway — берём ready-событие
    @Transactional
    public void seedDefaults() {
        createIfAbsent("FAST_250MS", 250, "Быстрые сигналы (аварии, токовые защиты)");
        createIfAbsent("NORMAL_1S", 1000, "Обычные технологические параметры");
        createIfAbsent("SLOW_10S", 10000, "Медленные параметры (уровни, температуры)");
        log.info("Interval profiles initialized: {} profiles", repository.count());
    }

    private void createIfAbsent(String name, int samplingMs, String desc) {
        if (!repository.existsByName(name)) {
            IntervalProfile p = new IntervalProfile();
            p.setName(name);
            p.setUaSamplingIntervalMs(samplingMs);
            p.setDescription(desc);
            repository.save(p);
        }
    }

    public List<IntervalProfile> findAll() {
        return repository.findAll();
    }

    public IntervalProfile findById(Long id) {
        return repository.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Profile not found: " + id));
    }

    @Transactional
    public IntervalProfile save(IntervalProfile p) {
        validate(p);
        // Уникальность имени вне БД-констрейнта — для человекочитаемой ошибки
        repository.findByName(p.getName()).ifPresent(existing -> {
            if (!existing.getId().equals(p.getId())) {
                throw new IllegalArgumentException("Имя профиля уже используется: " + p.getName());
            }
        });
        return repository.save(p);
    }

    @Transactional
    public void delete(Long id) {
        IntervalProfile p = findById(id);
        if (tagRepository.existsByIntervalProfileId((id))) {
            throw new IllegalStateException(
                    "Профиль используется тегами, сначала отвяжите: " + p.getName());
        }
        repository.delete(p);
    }

    private void validate(IntervalProfile p) {
        if (p.getName() == null || p.getName().isBlank())
            throw new IllegalArgumentException("Имя профиля обязательно");
        if (p.getUaSamplingIntervalMs() == null || p.getUaSamplingIntervalMs() < 100)
            throw new IllegalArgumentException("Sampling interval >= 100 мс");
        if (p.getMqttDeadband() != null && p.getMqttDeadband() < 0)
            throw new IllegalArgumentException("MQTT deadband >= 0");
        if (p.getMqttPublishIntervalMs() != null && p.getMqttPublishIntervalMs() < 100)
            throw new IllegalArgumentException("MQTT publish interval >= 100 мс");
    }
}