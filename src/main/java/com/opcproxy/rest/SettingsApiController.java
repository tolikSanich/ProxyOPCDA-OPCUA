package com.opcproxy.rest;

import com.opcproxy.ui.services.SettingsService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * REST API для управления настройками (ТЗ §5.9.3).
 */
@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(
        name = "Settings API",
        description = "Управление настройками системы (ТЗ §5.9.3)")
@RestController
@RequestMapping("/api/v1/settings")
@RequiredArgsConstructor
public class SettingsApiController {

    private final SettingsService settingsService;

    @Operation(summary = "Получить настройки секции")
    @GetMapping("/{section}")
    public ResponseEntity<Map<String, String>> getSettings(@PathVariable String section) {
        Map<String, String> settings = switch (section.toLowerCase()) {
            case "opcua" -> settingsService.getOpcUaSettings();
            case "mqtt" -> settingsService.getMqttSettings();
            case "opcda" -> settingsService.getOpcDaSettings();
            default -> settingsService.getSettings(section);
        };
        return ResponseEntity.ok(settings);
    }

    @Operation(summary = "Сохранить настройки секции")
    @PutMapping("/{section}")
    public ResponseEntity<Void> saveSettings(
            @PathVariable String section,
            @RequestBody Map<String, String> settings) {
        settingsService.saveSettings(section, settings);
        return ResponseEntity.ok().build();
    }

    @Operation(summary = "Удалить настройку")
    @DeleteMapping("/{section}/{key}")
    public ResponseEntity<Void> deleteSetting(
            @PathVariable String section,
            @PathVariable String key) {
        settingsService.deleteSetting(section + "." + key);
        return ResponseEntity.noContent().build();
    }
}