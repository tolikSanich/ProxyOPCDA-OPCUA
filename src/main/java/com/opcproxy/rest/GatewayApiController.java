package com.opcproxy.rest;

import com.opcproxy.opcda.ConnectionState;
import com.opcproxy.opcda.OpcDaClient;
import com.opcproxy.opcda.OpcDaConnectionManager;
import com.opcproxy.opcua.OpcUaServerManager;
import com.opcproxy.persistence.entity.IntervalProfile;
import com.opcproxy.rest.dto.RestDtos;
import com.opcproxy.rest.dto.RestDtos.ConnectionCreateRequest;
import com.opcproxy.rest.dto.RestDtos.ConnectionDto;
import com.opcproxy.rest.dto.RestDtos.StatusDto;
import com.opcproxy.rest.dto.RestDtos.TagDto;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.ui.services.ConnectionService;
import com.opcproxy.ui.services.IntervalProfileService;
import com.opcproxy.ui.services.TagService;
import io.swagger.v3.oas.annotations.Operation;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Optional;

@Slf4j
@io.swagger.v3.oas.annotations.tags.Tag(                          // <-- FQ-имя аннотации
        name = "Gateway API",
        description = "Управление подключениями и тегами шлюза (ТЗ §5.9)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class GatewayApiController {

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ISO_INSTANT;

    private final ConnectionService connectionService;
    private final IntervalProfileService profileService;
    private final TagService tagService;
    private final TagRegistry tagRegistry;
    private final OpcUaServerManager opcUaServerManager;
    private final OpcDaConnectionManager connectionManager;
    // ---------------- Status ----------------

    @Operation(summary = "Сводный статус шлюза")
    @GetMapping("/status")
    public StatusDto status() {
        var conns = connectionService.findAll();
        long connected = conns.stream()
                .filter(c -> connectionService.getStatus(c.getId()) == ConnectionState.CONNECTED)
                .count();
        var tags = tagService.findAll();
        long good = tags.stream()
                .filter(t -> tagRegistry.getTagValue(t.getId())
                        .map(v -> v.getQuality() != null && v.getQuality().startsWith("Good"))
                        .orElse(false))
                .count();
        String uaEndpoint = opcUaServerManager.isRunning()
                ? "opc.tcp://" + opcUaServerManager.getHostname() + ":" + opcUaServerManager.getBindPort()
                : null;
        return new StatusDto(conns.size(), (int) connected,
                tags.size(), (int) good, uaEndpoint);
    }
    @Operation(summary = "Браузинг тегов OPC DA (плоский список с кэшированием 60с, ТЗ §5.2.6, §5.9.3)")
    @GetMapping("/connections/{id}/browse")
    public ResponseEntity<List<RestDtos.BrowseNodeDto>> browse(@PathVariable Long id) {
        try {
            List<RestDtos.BrowseNodeDto> result = connectionManager.browse(id);
            return ResponseEntity.ok(result);
        } catch (IllegalStateException e) {
            log.warn("Browse failed (not connected) for connection {}: {}", id, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
        } catch (Exception e) {
            log.error("Browse failed for connection {}: {}", id, e.getMessage());
            throw new IllegalStateException("Ошибка браузинга OPC DA: " + e.getMessage());
        }
    }

    // ---------------- Connections ----------------

    @Operation(summary = "Список подключений со статусами")
    @GetMapping("/connections")
    public List<ConnectionDto> connections() {
        return connectionService.findAll().stream()
                .map(c -> ConnectionDto.of(c,
                        connectionService.getStatus(c.getId()),
                        connectionService.getErrorCount(c.getId())))
                .toList();
    }


    @Operation(summary = "Подключение по id")
    @GetMapping("/connections/{id}")
    public ConnectionDto connection(@PathVariable Long id) {
        var c = connectionService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Connection not found: " + id));
        return ConnectionDto.of(c, connectionService.getStatus(id),
                connectionService.getErrorCount(id));
    }

    @Operation(summary = "Создать подключение (пароль шифруется автоматически)")
    @PostMapping("/connections")
    public ResponseEntity<ConnectionDto> createConnection(@RequestBody ConnectionCreateRequest req) {
        if (connectionService.existsByName(req.name())) {
            throw new IllegalArgumentException("Connection name must be unique: " + req.name());
        }
        OpcDaConnection c = new OpcDaConnection();
        applyToEntity(c, req);
        c = connectionService.save(c);
        return ResponseEntity.created(URI.create("/api/v1/connections/" + c.getId()))
                .body(ConnectionDto.of(c, connectionService.getStatus(c.getId()),
                        connectionService.getErrorCount(c.getId())));
    }

    @Operation(summary = "Изменить подключение")
    @PutMapping("/connections/{id}")
    public ConnectionDto updateConnection(@PathVariable Long id,
                                          @RequestBody ConnectionCreateRequest req) {
        OpcDaConnection c = connectionService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Connection not found: " + id));
        applyToEntity(c, req);
        // пароль: пусто в запросе = не менять (политика Jasypt)
        if (req.password() == null || req.password().isBlank()) {
            c.setPasswordEncrypted(null);   // save() подставит старое из БД
        } else {
            c.setPasswordEncrypted(req.password());   // plaintext -> save() зашифрует
        }
        c = connectionService.save(c);
        return ConnectionDto.of(c, connectionService.getStatus(id),
                connectionService.getErrorCount(id));
    }

    @Operation(summary = "Удалить подключение")
    @DeleteMapping("/connections/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteConnection(@PathVariable Long id) {
        connectionService.delete(id);
    }

    @Operation(summary = "Переподключить")
    @PostMapping("/connections/{id}/reconnect")
    public ConnectionDto reconnect(@PathVariable Long id) {
        connectionService.reconnect(id);
        var c = connectionService.findById(id)
                .orElseThrow(() -> new java.util.NoSuchElementException("Connection not found: " + id));
        return ConnectionDto.of(c, connectionService.getStatus(id),
                connectionService.getErrorCount(id));
    }

    // ---------------- Tags ----------------

    @Operation(summary = "Список тегов с живыми значениями")
    @GetMapping("/tags")
    public List<TagDto> tags(@RequestParam(required = false) String connection) {
        return tagService.findAll().stream()
                .filter(t -> connection == null
                        || (t.getConnection() != null
                        && connection.equals(t.getConnection().getName())))
                .map(this::toDto)
                .toList();
    }

    @Operation(summary = "Тег по id")
    @GetMapping("/tags/{id}")
    public TagDto tag(@PathVariable Long id) {
        return tagService.findById(id)
                .map(this::toDto)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tag not found: " + id));
    }

    @Operation(summary = "Включить/выключить тег")
    @PostMapping("/tags/{id}/enabled")
    public TagDto setTagEnabled(@PathVariable Long id, @RequestParam boolean value) {
        tagService.setEnabled(id, value);
        return tagService.findById(id).map(this::toDto)
                .orElseThrow(() -> new java.util.NoSuchElementException("Tag not found: " + id));
    }

    @Operation(summary = "Профили интервалов OPC UA")
    @GetMapping("/interval-profiles")          // итоговый URL: /api/v1/interval-profiles ✅
    public List<IntervalProfile> profiles() {
        return profileService.findAll();
    }
    // ---------------- Helpers ----------------

    private void applyToEntity(OpcDaConnection c, ConnectionCreateRequest req) {
        c.setName(req.name());
        c.setHost(req.host());
        c.setProgIdOrClsid(req.progIdOrClsid());
        c.setUsername(req.username());
        c.setDomain(req.domain());
        c.setEnabled(req.enabled());
    }

    private TagDto toDto(Tag t) {          // <-- здесь Tag = entity, теперь корректно
        var tv = tagRegistry.getTagValue(t.getId()).orElse(null);
        return TagDto.of(t,
                tv != null ? tv.getValue() : null,
                tv != null ? tv.getQuality() : "Bad_NotRegistered",
                tv != null && tv.getTimestamp() != null ? TS_FMT.format(tv.getTimestamp()) : null);
    }

}