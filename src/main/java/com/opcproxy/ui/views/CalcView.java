package com.opcproxy.ui.views;

import com.opcproxy.calc.CalcDependencyValidator;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.IntervalProfileRepository;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Hr;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Экран работы с вычисляемыми тегами (ТЗ §5.8.3.5).
 */
@Slf4j
@PageTitle("Calculations")
@Route(value = "calc", layout = MainLayout.class)
@PermitAll
public class CalcView extends VerticalLayout {

    private final TagService tagService;
    private final TagRegistry tagRegistry;
    private final CalcDependencyValidator calcValidator;

    private final Grid<Tag> grid = new Grid<>();
    private final TextField filterField = new TextField("Фильтр по имени или формуле");

    private final Map<Long, Span> valueSpans = new ConcurrentHashMap<>();
    private final Map<Long, Span> qualitySpans = new ConcurrentHashMap<>();
    private Registration pollRegistration;
    private final TagDialog tagDialog;

    public CalcView(TagService tagService,
                    TagRegistry tagRegistry,
                    CalcDependencyValidator calcValidator,
                    OpcDaConnectionRepository connectionRepository,
                    IntervalProfileRepository profileRepository) {
        this.tagService = tagService;
        this.tagRegistry = tagRegistry;
        this.calcValidator = calcValidator;

        // Инициализируем диалог с реальными репозиториями, чтобы избежать NPE в TagDialog
        this.tagDialog = new TagDialog(
                tagService,
                connectionRepository,
                calcValidator,
                this::refreshGrid,
                profileRepository
        );

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setSizeFull();

        createHeader();
        createFilters();
        createGrid();
        refreshGrid();
    }

    private void createHeader() {
        H2 title = new H2("Вычисляемые теги (Calculation Engine)");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);

        Button addButton = new Button("Добавить CALC-тег", new Icon(VaadinIcon.PLUS_CIRCLE));
        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addButton.addClickListener(e -> {
            Tag newTag = new Tag();
            newTag.setSourceType(SourceType.CALC);
            tagDialog.edit(newTag);
        });

        Button validateAllButton = new Button("Проверить все формулы", new Icon(VaadinIcon.CHECK));
        validateAllButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);
        validateAllButton.addClickListener(e -> validateAllFormulas());

        HorizontalLayout header = new HorizontalLayout(title, addButton, validateAllButton);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header);
        add(new Hr());
    }

    private void createFilters() {
        filterField.setPlaceholder("Введите имя тега или часть формулы...");
        filterField.setClearButtonVisible(true);
        filterField.setValueChangeMode(ValueChangeMode.LAZY);
        filterField.setValueChangeTimeout(300);
        filterField.addValueChangeListener(e -> refreshGrid());
        filterField.setWidth("400px");
        add(filterField);
    }

    private void createGrid() {
        grid.setWidthFull();
        grid.setHeight("100%");
        grid.setSelectionMode(Grid.SelectionMode.NONE);

        grid.addColumn(Tag::getName)
                .setHeader("Имя тега")
                .setFlexGrow(1)
                .setSortable(true);

        grid.addColumn(Tag::getExpression)
                .setHeader("Выражение (SpEL)")
                .setFlexGrow(2)
                .setSortable(true)
                .setRenderer(new com.vaadin.flow.data.renderer.ComponentRenderer<>(tag -> {
                    Span span = new Span(tag.getExpression() != null ? tag.getExpression() : "—");
                    span.getStyle().set("fontFamily", "var(--lumo-font-family-monospace)");
                    span.getStyle().set("fontSize", "var(--lumo-font-size-s)");
                    return span;
                }));

        grid.addColumn(tag -> {
                    List<String> refs = calcValidator.extractRefs(tag.getExpression());
                    return refs.isEmpty() ? "—" : String.join(", ", refs);
                })
                .setHeader("Зависимости")
                .setFlexGrow(1);

        grid.addComponentColumn(this::createValueSpan)
                .setHeader("Значение")
                .setFlexGrow(1);

        grid.addComponentColumn(this::createQualityBadge)
                .setHeader("Качество")
                .setFlexGrow(1);

        grid.addComponentColumn(this::createActions)
                .setHeader("Действия")
                .setFlexGrow(0)
                .setWidth("120px");

        add(grid);
    }

    private void validateAllFormulas() {
        List<Tag> calcTags = tagService.findAll().stream()
                .filter(t -> t.getSourceType() == SourceType.CALC)
                .toList();

        int validCount = 0;
        int invalidCount = 0;
        StringBuilder errorReport = new StringBuilder();

        for (Tag tag : calcTags) {
            var result = calcValidator.validate(tag);
            if (result.ok()) {
                validCount++;
            } else {
                invalidCount++;
                errorReport.append("• ").append(tag.getName()).append(": ").append(String.join("; ", result.errors())).append("\n");
            }
        }

        if (invalidCount == 0) {
            Notification.show("Все " + validCount + " формул прошли валидацию успешно", 3000, Notification.Position.BOTTOM_END);
        } else {
            Notification.show("Найдено ошибок: " + invalidCount + ". Проверьте логи или исправьте теги.", 5000, Notification.Position.MIDDLE);
            log.warn("Calc Validation Errors:\n{}", errorReport.toString());
        }
    }

    private Span createValueSpan(Tag tag) {
        Span span = new Span("—");
        span.addClassNames(LumoUtility.FontSize.SMALL);
        valueSpans.put(tag.getId(), span);
        applyValue(tag.getId());
        return span;
    }

    private Span createQualityBadge(Tag tag) {
        Span span = new Span("Unknown");
        span.addClassNames(
                LumoUtility.Padding.Horizontal.SMALL,
                LumoUtility.Padding.Vertical.XSMALL,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.FontSize.XSMALL,
                LumoUtility.FontWeight.BOLD
        );
        qualitySpans.put(tag.getId(), span);
        applyQuality(tag.getId());
        return span;
    }

    private HorizontalLayout createActions(Tag tag) {
        Button editBtn = new Button(new Icon(VaadinIcon.EDIT));
        editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        editBtn.setTooltipText("Редактировать формулу");
        editBtn.addClickListener(e -> tagService.findById(tag.getId()).ifPresent(tagDialog::edit));

        HorizontalLayout layout = new HorizontalLayout(editBtn);
        layout.setSpacing(false);
        layout.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        return layout;
    }

    private void applyValue(Long tagId) {
        Span span = valueSpans.get(tagId);
        if (span == null) return;

        String newText = tagRegistry.getTagValue(tagId)
                .map(tv -> tv.getValue() != null ? String.valueOf(tv.getValue()) : "—")
                .orElse("—");

        if (!newText.equals(span.getText())) {
            getUI().ifPresent(ui -> ui.access(() -> span.setText(newText)));
        }
    }

    private void applyQuality(Long tagId) {
        Span span = qualitySpans.get(tagId);
        if (span == null) return;

        String quality = tagRegistry.getTagValue(tagId)
                .map(com.opcproxy.tags.TagRegistry.TagValue::getQuality)
                .orElse("Bad_NotRegistered");

        if (!quality.equals(span.getText())) {
            getUI().ifPresent(ui -> ui.access(() -> {
                span.setText(quality);
                span.getStyle().set("color", getColorForQuality(quality));
                span.getStyle().set("background-color", getBgColorForQuality(quality) + "20");
            }));
        }
    }

    private String getColorForQuality(String q) {
        if (q.startsWith("Good")) return "var(--lumo-success-color)";
        if (q.startsWith("Uncertain")) return "var(--lumo-warning-color)";
        return "var(--lumo-error-color)";
    }

    private String getBgColorForQuality(String q) {
        if (q.startsWith("Good")) return "var(--lumo-success-color)";
        if (q.startsWith("Uncertain")) return "var(--lumo-warning-color)";
        return "var(--lumo-error-color)";
    }

    private void refreshGrid() {
        String filter = filterField.getValue() != null ? filterField.getValue().toLowerCase() : "";

        List<Tag> filteredTags = tagService.findAll().stream()
                .filter(t -> t.getSourceType() == SourceType.CALC)
                .filter(t -> filter.isEmpty() ||
                        (t.getName() != null && t.getName().toLowerCase().contains(filter)) ||
                        (t.getExpression() != null && t.getExpression().toLowerCase().contains(filter)))
                .collect(Collectors.toList());

        valueSpans.clear();
        qualitySpans.clear();
        grid.setItems(filteredTags);
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();
        ui.setPollInterval(1000);
        pollRegistration = ui.addPollListener(e -> {
            valueSpans.keySet().forEach(this::applyValue);
            qualitySpans.keySet().forEach(this::applyQuality);
        });
    }

    @Override
    protected void onDetach(DetachEvent event) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
        super.onDetach(event);
    }
}