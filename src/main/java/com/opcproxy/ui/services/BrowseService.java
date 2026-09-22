package com.opcproxy.ui.services;

import com.opcproxy.opcda.OpcDaConnectionManager;
import com.opcproxy.rest.dto.RestDtos.BrowseNodeDto;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Collections;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BrowseService {
    private final OpcDaConnectionManager connectionManager;

    public List<BrowseNodeDto> getNodes(Long connectionId, BrowseNodeDto parent) {
        try {
            // Если parent null, получаем корни
            if (parent == null) {
                return connectionManager.browse(connectionId);
            }
            // Если parent задан, ищем его детей
            return parent.children();
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}