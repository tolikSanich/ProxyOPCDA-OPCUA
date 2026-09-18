package com.opcproxy.ui.views;

import com.opcproxy.csv.CsvTagService;
import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.html.Anchor;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.radiobutton.RadioButtonGroup;
import com.vaadin.flow.component.upload.Upload;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.StreamResource;
import com.vaadin.flow.server.streams.UploadHandler;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;

import java.io.ByteArrayInputStream;

@PageTitle("Import / Export")
@Route(value = "importexport", layout = MainLayout.class)
@PermitAll
public class ImportExportView extends VerticalLayout {

    private final CsvTagService csvTagService;
    private final TagService tagService;

    private final RadioButtonGroup<String> mode = new RadioButtonGroup<>();
    private String modeValue = "merge";

    public ImportExportView(CsvTagService csvTagService, TagService tagService) {
        this.csvTagService = csvTagService;
        this.tagService = tagService;

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        add(new H2("CSV Import / Export"));

        // --- Экспорт (до этапа 4 — StreamResource; заменим на REST-эндпоинт) ---
        Anchor download = new Anchor(streamResource("tags-export.csv", this::safeExport), "");
        download.getElement().setAttribute("download", true);
        download.add(new Button("Export all tags",
                e -> Notification.show("Экспорт готов — файл скачивается")));

        Anchor tpl = new Anchor(streamResource("tags-template.csv", this::safeTemplate), "");
        tpl.getElement().setAttribute("download", true);
        Button tplBtn = new Button("Download template");   // <-- ФИКС: нет конструктора с Variant
        tplBtn.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        tpl.add(tplBtn);

        add(new HorizontalLayout(download, tpl));

        // --- Режим импорта ---
        mode.setItems("merge", "replace");
        mode.setValue("merge");
        mode.addValueChangeListener(e -> modeValue = e.getValue());
        add(mode);

        // --- Загрузка: новый API UploadHandler (Vaadin 24.4+/25), не deprecated ---
        Upload upload = new Upload();
        upload.setAcceptedFileExtensions(".csv");
        upload.setUploadHandler((UploadHandler) ctx -> {
            try {
                var report = csvTagService.importCsv(ctx.getInputStream(),
                        "replace".equals(modeValue));
                ImportExportView.this.getUI().ifPresent(ui -> ui.access(() -> ImportExportView.this.show(report)));
            } catch (Exception e) {
                ImportExportView.this.getUI().ifPresent(ui -> ui.access(() ->
                        Notification.show("Ошибка импорта: " + e.getMessage(), 5000,
                                Notification.Position.MIDDLE)));
            }
        });
        add(upload);
    }

    private void show(CsvTagService.ImportReport r) {
        Notification.show("Total: %d, created: %d, updated: %d, errors: %d"
                        .formatted(r.total(), r.created(), r.updated(), r.errors().size()),
                5000, Notification.Position.MIDDLE);
        r.errors().stream().limit(5).forEach(e ->
                Notification.show("Строка " + e.line() + " [" + e.tagName() + "]: " + e.error(),
                        8000, Notification.Position.MIDDLE));
    }

    /** Осознанное использование deprecated StreamResource до появления REST-эндпоинта экспорта. */
    @SuppressWarnings("removal")
    private StreamResource streamResource(String name, java.util.function.Supplier<byte[]> bytes) {
        return new StreamResource(name, () -> new ByteArrayInputStream(bytes.get()));
    }

    private byte[] safeExport() {
        try {
            return csvTagService.exportAll();
        } catch (Exception e) { return new byte[0];
        }
    }

    private byte[] safeTemplate() {
        try { return csvTagService.template(); } catch (Exception e) { return new byte[0]; }
    }
}