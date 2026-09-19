package com.opcproxy.rest;

import com.opcproxy.csv.CsvTagService;
import com.opcproxy.rest.dto.RestDtos.ImportResultDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;

@Tag(name = "CSV", description = "Импорт/экспорт тегов (ТЗ §5.7, §5.9)")
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class CsvApiController {

    private final CsvTagService csvTagService;

    @Operation(summary = "Экспорт всех тегов в CSV")
    @GetMapping(value = "/export/csv", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> exportCsv() throws Exception {
        byte[] body = csvTagService.exportAll();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tags-export.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }

    @Operation(summary = "Скачать шаблон CSV")
    @GetMapping(value = "/export/template", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<byte[]> template() throws Exception {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=tags-template.csv")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(csvTagService.template());
    }

    @Operation(summary = "Импорт CSV (multipart; mode=merge|replace)")
    @PostMapping(value = "/import/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportResultDto importCsv(@RequestParam("file") MultipartFile file,
                                     @RequestParam(defaultValue = "merge") String mode) throws Exception {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Файл не передан");
        }
        if (!"merge".equals(mode) && !"replace".equals(mode)) {
            throw new IllegalArgumentException("mode должен быть merge или replace");
        }
        var report = csvTagService.importCsv(file.getInputStream(), "replace".equals(mode));
        if (report.hasErrors()) {
            // Ошибки валидации: 422 с деталями первой ошибки (полный отчёт — в лог)
            throw new IllegalArgumentException("Ошибки валидации: "
                    + report.errors().get(0).line() + ": " + report.errors().get(0).error());
        }
        return new ImportResultDto(report.total(), report.created(), report.updated(), 0);
    }
}