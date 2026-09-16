package com.opcproxy.ui.services;

import com.opcproxy.opcda.OpcDaConnectionManager;
import com.opcproxy.opcua.OpcUaServerManager;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Сервис для агрегации данных, необходимых для отображения на главной панели мониторинга (Dashboard).
 */
@Service
@RequiredArgsConstructor
public class DashboardService {

    private final OpcDaConnectionManager connectionManager;
    private final OpcUaServerManager opcUaServerManager;
    private final TagRepository tagRepository;
    private final TagRegistry tagRegistry;

    /**
     * DTO для отображения статуса OPC DA подключения в UI.
     */
    public record ConnectionStatusDto(
            Long id,
            String name,
            String host,
            String state,
            int errorCount
    ) {}

    /**
     * Получает список статусов всех OPC DA подключений, находящихся в менеджере.
     */
    public List<ConnectionStatusDto> getConnectionStatuses() {
        return connectionManager.getAllClients().values().stream()
                .map(client -> new ConnectionStatusDto(
                        client.getConnectionConfig().getId(),
                        client.getConnectionConfig().getName(),
                        client.getConnectionConfig().getHost(),
                        client.getState().name(),
                        client.getErrorCount()
                ))
                .collect(Collectors.toList());
    }

    /**
     * DTO для сводной статистики по качеству тегов.
     */
    public record TagStatsDto(
            long total,
            long good,
            long bad,
            long uncertain
    ) {}

    /**
     * Подсчитывает количество тегов в разрезе их текущего качества (Good/Bad/Uncertain).
     * Данные берутся из реестра тегов в памяти (TagRegistry).
     */
    public TagStatsDto getTagStats() {
        List<Tag> tags = tagRepository.findAllEnabled();
        long total = tags.size();
        long good = 0, bad = 0, uncertain = 0;

        for (Tag tag : tags) {
            // Получаем качество из реестра, если тега нет в реестре - считаем Bad
            String quality = tagRegistry.getTagValue(tag.getId())
                    .map(TagRegistry.TagValue::getQuality)
                    .orElse("Bad");

            if (quality.startsWith("Good")) {
                good++;
            } else if (quality.startsWith("Bad")) {
                bad++;
            } else if (quality.startsWith("Uncertain")) {
                uncertain++;
            }
        }

        return new TagStatsDto(total, good, bad, uncertain);
    }

    /**
     * Проверяет, запущен ли встроенный OPC UA сервер.
     */
    public boolean isOpcUaServerRunning() {
        return opcUaServerManager.isRunning();
    }

    /**
     * Возвращает порт, на котором слушает OPC UA сервер.
     */
    public int getOpcUaServerPort() {
        return opcUaServerManager.getBindPort();
    }
}