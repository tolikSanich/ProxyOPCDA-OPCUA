package com.opcproxy.ui.services;

import com.opcproxy.persistence.entity.SystemSetting;
import com.opcproxy.persistence.repository.SystemSettingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Сервис управления настройками системы (ТЗ §5.8.3.7, §5.9.3).
 * Хранит настройки в таблице system_setting (key-value).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SettingsService {

    private final SystemSettingRepository repository;

    /**
     * Получить все настройки указанной секции.
     */
    public Map<String, String> getSettings(String section) {
        List<SystemSetting> settings = repository.findAll();
        Map<String, String> result = new HashMap<>();

        String prefix = section + ".";
        for (SystemSetting setting : settings) {
            if (setting.getKey().startsWith(prefix)) {
                String key = setting.getKey().substring(prefix.length());
                result.put(key, setting.getValue());
            }
        }

        return result;
    }

    /**
     * Получить конкретную настройку.
     */
    public Optional<String> getSetting(String key) {
        return repository.findById(key).map(SystemSetting::getValue);
    }

    /**
     * Сохранить настройки секции (merge).
     */
    @Transactional
    public void saveSettings(String section, Map<String, String> settings) {
        for (Map.Entry<String, String> entry : settings.entrySet()) {
            String fullKey = section + "." + entry.getKey();
            SystemSetting setting = repository.findById(fullKey)
                    .orElse(new SystemSetting());
            setting.setKey(fullKey);
            setting.setValue(entry.getValue());
            repository.save(setting);
        }
        log.info("Settings saved for section '{}': {} keys", section, settings.size());
    }

    /**
     * Удалить настройку.
     */
    @Transactional
    public void deleteSetting(String key) {
        repository.deleteById(key);
        log.info("Setting deleted: {}", key);
    }

    /**
     * Получить настройки OPC UA.
     */
    public Map<String, String> getOpcUaSettings() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("port", "4840");
        defaults.put("hostname", "localhost");
        defaults.put("enabledPolicies", "None,Basic256Sha256");
        defaults.put("trustAllClients", "true");
        defaults.put("defaultSamplingIntervalMs", "500");

        Map<String, String> stored = getSettings("opcua");
        defaults.putAll(stored);
        return defaults;
    }

    /**
     * Получить настройки MQTT.
     */
    public Map<String, String> getMqttSettings() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("enabled", "false");
        defaults.put("broker", "tcp://localhost:1883");
        defaults.put("baseTopic", "gateway/default");
        defaults.put("qos", "1");
        defaults.put("retain", "false");
        defaults.put("configRefreshMs", "5000");

        Map<String, String> stored = getSettings("mqtt");
        defaults.putAll(stored);
        return defaults;
    }

    /**
     * Получить настройки OPC DA.
     */
    public Map<String, String> getOpcDaSettings() {
        Map<String, String> defaults = new HashMap<>();
        defaults.put("defaultRefreshPeriodMs", "1000");
        defaults.put("reconnectIntervalMs", "5000");
        defaults.put("maxConnections", "10");
        defaults.put("connectionTimeoutMs", "10000");

        Map<String, String> stored = getSettings("opcda");
        defaults.putAll(stored);
        return defaults;
    }
}