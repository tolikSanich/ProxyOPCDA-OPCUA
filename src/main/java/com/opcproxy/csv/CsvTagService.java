package com.opcproxy.csv;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.ReadMode;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.ui.services.TagService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.apache.commons.csv.CSVRecord;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Импорт/экспорт тегов CSV (ТЗ §5.7).
 * Формат: ; UTF-8; кавычки и экранирование — Apache Commons CSV.
 * Режимы импорта: merge (update+insert) и replace (транзакционная замена).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CsvTagService {
    /** UTF-8 Byte Order Mark — для корректного открытия в Excel с кириллицей. */
    private static final byte[] BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
    private final TagRepository tagRepository;
    private final TagService tagService;
    private final OpcDaConnectionRepository connectionRepository;

    /** Колонки строго по ТЗ §5.7.3. */
    private static final String[] HEADERS = {
            "tagName", "sourceType", "connectionName", "sourceItemId", "expression",
            "dataType", "readMode", "refreshPeriodMs", "uaSamplingIntervalMs",
            "intervalProfileName", "publishMqtt", "mqttDeadband", "description"
    };

    private CSVFormat format() {
        return CSVFormat.DEFAULT.builder()
                .setDelimiter(';')
                .setHeader(HEADERS)
                .setSkipHeaderRecord(true)
                .setTrim(true)
                .build();
    }

    // ------------------------------------------------------------------
    // Экспорт
    // ------------------------------------------------------------------

// В format() НИЧЕГО не меняем — заголовок колонок остаётся первой распознаваемой строкой.
// Комментарии фильтруются ВРУЧНУЮ до парсинга, т.к. Commons CSV не умеет skip-комментарии
// при произвольном первом символе (setCommentMarker требует фиксированный символ в начале записи).

    public byte[] exportAll() throws IOException {
        List<Tag> tags = tagRepository.findAll();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(BOM);
        try (var printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format())) {
            // ШАПКА (2 строки-комментария)
            printer.printComment("OPC DA-UA Proxy — Tag Export v1");
            printer.printComment("Generated: " + java.time.LocalDateTime.now()
                    + "; tags: " + tags.size() + "; delimiter: ';'; encoding: UTF-8");
            for (Tag t : tags) {
                printer.printRecord(/* ...как было... */);
            }
        }
        return out.toByteArray();
    }

    public byte[] template() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(BOM);
        try (var printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format())) {
            printer.printComment("OPC DA-UA Proxy — Tag Import Template v1");
            printer.printComment("Строки, начинающиеся с '#', игнорируются. Разделитель: ';'.");

            // --- Базовые DA ---
            printer.printRecord("Tank1_Level",   "DA", "OPC.OpcTestServer_x86.1", "Test/Float", "", "FLOAT",   "ASYNC", 1000, "", "", false, "", "Уровень в танке (сырой тег)");
            printer.printRecord("Tank1_Temp",    "DA", "OPC.OpcTestServer_x86.1", "Test/Int32", "", "INT32",   "ASYNC", 1000, "", "", false, "", "Температура, °C");
            printer.printRecord("Motor_Running", "DA", "opcserversim.Instance.1", "BooleanValue", "", "BOOLEAN", "ASYNC", 500, "", "", false, "", "Двигатель работает");

            // --- Арифметика ---
            printer.printRecord("Tank1_Volume",      "CALC", "", "", "#Tank1_Level * 2.5",                        "DOUBLE", "SYNC", 1000, "", "", false, "", "Объём = уровень * коэффициент");
            printer.printRecord("Tank1_LevelPercent","CALC", "", "", "#Tank1_Level / 100 * 100",                  "DOUBLE", "SYNC", 1000, "", "", false, "", "Процент заполнения");
            printer.printRecord("TempFahrenheit",    "CALC", "", "", "#Tank1_Temp * 1.8 + 32",                    "DOUBLE", "SYNC", 1000, "", "", false, "", "Конвертация в °F");
            printer.printRecord("Sum2Tags",          "CALC", "", "", "#Tank1_Level + #Tank1_Temp",                "DOUBLE", "SYNC", 1000, "", "", false, "", "Сумма двух тегов");

            // --- Сравнения / уставки (результат BOOLEAN) ---
            printer.printRecord("Tank1_HighAlarm",   "CALC", "", "", "#Tank1_Level > 80",                         "BOOLEAN","SYNC", 1000, "", "", false, "", "Верхняя уставка");
            printer.printRecord("Tank1_LowAlarm",    "CALC", "", "", "#Tank1_Level < 20",                         "BOOLEAN","SYNC", 1000, "", "", false, "", "Нижняя уставка");
            printer.printRecord("TempNormalRange",   "CALC", "", "", "#Tank1_Temp >= 18 and #Tank1_Temp <= 25",   "BOOLEAN","SYNC", 1000, "", "", false, "", "В норме (диапазон)");

            // --- Логика ---
            printer.printRecord("AnyAlarm",          "CALC", "", "", "#Tank1_HighAlarm or #Tank1_LowAlarm",       "BOOLEAN","SYNC", 500, "", "", false, "", "Хотя бы одна тревога");
            printer.printRecord("AllOk",             "CALC", "", "", "#TempNormalRange and #Motor_Running",       "BOOLEAN","SYNC", 1000, "", "", false, "", "Всё в норме");
            printer.printRecord("NotRunning",        "CALC", "", "", "not #Motor_Running",                        "BOOLEAN","SYNC", 1000, "", "", false, "", "Инверсия");

            // --- Условный выбор (ternary) ---
            printer.printRecord("LevelStatus",       "CALC", "", "", "#Tank1_Level > 80 ? 'HIGH' : (#Tank1_Level < 20 ? 'LOW' : 'OK')", "STRING", "SYNC", 1000, "", "", false, "", "Текстовый статус");
            printer.printRecord("AlarmCode",         "CALC", "", "", "#Tank1_HighAlarm ? 1 : (#Tank1_LowAlarm ? 2 : 0)", "INT32", "SYNC", 500, "", "", false, "", "Код тревоги");

            // --- Строки и конкатенация ---
            printer.printRecord("LevelText",         "CALC", "", "", "'Level=' + #Tank1_Level",                  "STRING", "SYNC", 2000, "", "", false, "", "Конкатенация для отображения");

            // --- Функции (Math, строки, cast) ---
            printer.printRecord("TempAbs",           "CALC", "", "", "T(java.lang.Math).abs(#Tank1_Temp)",        "INT32",  "SYNC", 1000, "", "", false, "", "Модуль значения");
            printer.printRecord("LevelRounded",      "CALC", "", "", "T(java.lang.Math).round(#Tank1_Level)",     "INT32",  "SYNC", 1000, "", "", false, "", "Округление");
            printer.printRecord("LevelRoot",         "CALC", "", "", "T(java.lang.Math).sqrt(#Tank1_Level)",      "DOUBLE", "SYNC", 2000, "", "", false, "", "Квадратный корень");
            printer.printRecord("LevelIntCast",      "CALC", "", "", "#Tank1_Level.intValue()",                   "INT32",  "SYNC", 1000, "", "", false, "", "Cast Double->Int");
            printer.printRecord("MotorText",         "CALC", "", "", "#Motor_Running.toString()",                 "STRING", "SYNC", 2000, "", "", false, "", "Boolean->String");
        }
        return out.toByteArray();
    }
    /** Пропускать строки-комментарии, начинающиеся с '#'. */
    private static boolean isComment(String firstCell) {
        return firstCell != null && firstCell.startsWith("#");
    }

    // ------------------------------------------------------------------
    // Импорт
    // ------------------------------------------------------------------

    public record ImportRow(int line, String tagName, String error) {
        public boolean ok() { return error == null; }
    }

    public record ImportReport(int total, int created, int updated, List<ImportRow> errors) {
        public boolean hasErrors() { return !errors.isEmpty(); }
    }

    /** Валидация без применения (предпросмотр). */
    public ImportReport validate(InputStream csv) throws IOException {
        return doImport(csv, null, false);
    }

    @Transactional
    public ImportReport importCsv(InputStream csv, boolean replace) throws IOException {
        return doImport(csv, replace, true);
    }

    private ImportReport doImport(InputStream csv, Boolean replace, boolean apply) throws IOException {
        List<ImportRow> errors = new ArrayList<>();
        List<Tag> parsed = new ArrayList<>();
        Map<String, OpcDaConnection> connByName = new HashMap<>();
        connectionRepository.findAll().forEach(c -> connByName.put(c.getName(), c));

        int line = 1; // заголовок
        for (CSVRecord rec : format().parse(new InputStreamReader(csv, StandardCharsets.UTF_8))) {
            line++;
            if (rec.size() > 0 && isComment(rec.get(0))) continue;   // шапка/комментарии
            String tag = rec.get("tagName");
            try {
                Tag t = parseRow(rec, connByName);
                parsed.add(t);
            } catch (Exception e) {
                errors.add(new ImportRow(line, tag, e.getMessage()));
            }
        }

        // Дубликаты имен внутри файла
        Set<String> seen = new HashSet<>();
        // no-op для читаемости
        for (Tag t : parsed) {
            if (!seen.add(t.getName())) {
                errors.add(new ImportRow(0, t.getName(), "Дубликат имени внутри файла"));
            }
        }

        int created = 0, updated = 0;
        if (apply && !errors.isEmpty()) {
            return new ImportReport(parsed.size(), 0, 0, errors); // валидация до применения
        }
        if (apply) {
            if (Boolean.TRUE.equals(replace)) {
                for (Tag t : tagRepository.findAll()) {
                    tagService.delete(t.getId());
                }
            }
            for (Tag t : parsed) {
                Optional<Tag> existing = tagRepository.findByName(t.getName());
                if (existing.isPresent()) {
                    t.setId(existing.get().getId());
                    t.setVersion(existing.get().getVersion());
                    tagService.save(t);   // merge
                    updated++;
                } else {
                    tagService.save(t);
                    created++;
                }
            }
            log.info("CSV import: created={}, updated={}, replace={}", created, updated, replace);
        }
        return new ImportReport(parsed.size(), created, updated, errors);
    }

    private Tag parseRow(CSVRecord rec, Map<String, OpcDaConnection> connByName) {
        Tag t = new Tag();
        t.setName(require(rec, "tagName"));
        SourceType st = SourceType.valueOf(require(rec, "sourceType").toUpperCase());
        t.setSourceType(st);

        String connName = rec.get("connectionName");
        if (st == SourceType.DA) {
            if (connName == null || connName.isBlank()) {
                throw new IllegalArgumentException("Для DA-тега обязателен connectionName");
            }
            OpcDaConnection conn = connByName.get(connName);
            if (conn == null) {
                throw new IllegalArgumentException("Подключение не найдено: " + connName);
            }
            t.setConnection(conn);
            t.setSourceItemId(require(rec, "sourceItemId"));
        } else { // CALC
            String expr = require(rec, "expression");
            t.setExpression(expr);
            // Валидация выражения — подключится на этапе 3 (CalcValidationService)
        }

        t.setDataType(DataType.valueOf(require(rec, "dataType").toUpperCase()));
        String rm = rec.get("readMode");
        t.setReadMode(rm == null || rm.isBlank() ? ReadMode.ASYNC : ReadMode.valueOf(rm.toUpperCase()));
        t.setRefreshPeriodMs(parseInt(rec.get("refreshPeriodMs"), 1000));
        String usi = rec.get("uaSamplingIntervalMs");
        t.setUaSamplingIntervalMs(usi == null || usi.isBlank() ? null : Integer.parseInt(usi));
        t.setPublishMqtt(parseBool(rec.get("publishMqtt"), false));
        String db = rec.get("mqttDeadband");
        t.setMqttDeadband(db == null || db.isBlank() ? null : Float.parseFloat(db));
        t.setDescription(rec.get("description"));
        t.setEnabled(true);
        return t;
    }

    private static String require(CSVRecord rec, String col) {
        String v = rec.get(col);
        if (v == null || v.isBlank()) throw new IllegalArgumentException("Обязательное поле: " + col);
        return v.trim();
    }

    private static Integer parseInt(String v, int def) {
        try { return v == null || v.isBlank() ? def : Integer.parseInt(v.trim()); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Не число: " + v); }
    }

    private static boolean parseBool(String v, boolean def) {
        return v == null || v.isBlank() ? def
                : v.equalsIgnoreCase("true") || v.equalsIgnoreCase("да") || v.equals("1");
    }

    private static String nvl(Object o) { return o == null ? "" : String.valueOf(o); }
}