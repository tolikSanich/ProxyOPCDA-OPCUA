package com.opcproxy.ui.views;

import com.opcproxy.calc.CalcDependencyValidator;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.IntervalProfileRepository;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.DetachEvent;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@PageTitle("Tags Management")
@Route(value = "tags", layout = com.opcproxy.ui.MainLayout.class)
@PermitAll
//@Component // <-- КЛЮЧЕВОЕ ИСПРАВЛЕНИЕ: регистрирует класс как Spring Bean
public class TagsView extends VerticalLayout {

    private final TagService tagService;
    private final TagRegistry tagRegistry;
    private final OpcDaConnectionRepository connectionRepository;
    private final IntervalProfileRepository profileRepository;
    private final CalcDependencyValidator calcValidator;   // опционально, но рекомендую

    private final Grid<Tag> grid = new Grid<>();
    private final TagDialog dialog;

    // Ссылки на Span со значением по id тега — для живого обновления
    private final Map<Long, Span> valueSpans = new ConcurrentHashMap<>();
    private Registration pollRegistration;

    // Конструктор теперь управляется Spring через @RequiredArgsConstructor,
    // но мы можем явно его объявить для инициализации dialog, если нужно,
    // или инициализировать dialog прямо в поле.
    public TagsView(TagService tagService, TagRegistry tagRegistry, OpcDaConnectionRepository connectionRepository, IntervalProfileRepository profileRepository, CalcDependencyValidator calcValidator) {
        this.tagService = tagService;
        this.tagRegistry = tagRegistry;
        this.connectionRepository = connectionRepository;
        this.profileRepository = profileRepository;
        this.calcValidator = calcValidator;

        // Инициализируем диалог
        this.dialog = new TagDialog(tagService, connectionRepository,
                calcValidator, this::refreshGrid, profileRepository);

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setWidthFull();

        createHeader();
        createGrid();
        createContextMenu();
        refreshGrid();
    }

    private void createHeader() {
        H2 title = new H2("Tags Configuration");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);

        Button addButton = new Button("Add Tag", new Icon(VaadinIcon.PLUS_CIRCLE));
        addButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addButton.addClickListener(e -> dialog.edit(null));

        Button refreshButton = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
        refreshButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        refreshButton.addClickListener(e -> refreshGrid());

        HorizontalLayout header = new HorizontalLayout(title, addButton, refreshButton);
        header.setWidthFull();
        header.setAlignItems(FlexComponent.Alignment.CENTER);
        header.expand(title);
        add(header);
    }

    private void createGrid() {
        grid.setWidthFull();
        grid.setHeight("600px");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        grid.addColumn(Tag::getName).setHeader("Name")
                .setFlexGrow(2)
                .setSortable(true);
        grid.addColumn(tag -> tag.getSourceType().name())
                .setHeader("Type").setFlexGrow(1);
        grid.addColumn(tag -> tag.getConnection() != null ? tag.getConnection()
                .getName() : "-").setHeader("Connection").setFlexGrow(1);
        grid.addColumn(Tag::getSourceItemId).setHeader("Item ID").setFlexGrow(2);

        // Колонка значения — Span сохраняем в map и обновляем по poll
        grid.addComponentColumn(tag -> {
            Span valueSpan = new Span();
            valueSpan.addClassName(LumoUtility.FontSize.SMALL);
            valueSpans.put(tag.getId(), valueSpan);
            applyValue(tag.getId());
            return valueSpan;
        }).setHeader("Current Value").setFlexGrow(1);

        grid.addComponentColumn(this::createQualityBadge).setHeader("Quality").setFlexGrow(1);
        grid.addColumn(tag -> tag.getRefreshPeriodMs() + " ms").setHeader("Polling").setFlexGrow(1);

        grid.addComponentColumn(tag -> {
            Span badge = new Span(tag.getEnabled() ? "YES" : "NO");
            badge.addClassNames(LumoUtility.Padding.Horizontal.SMALL, LumoUtility.Padding.Vertical.XSMALL, LumoUtility.BorderRadius.MEDIUM, LumoUtility.FontSize.XSMALL, LumoUtility.FontWeight.BOLD);
            if (tag.getEnabled()) {
                badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
                badge.getStyle().set("color", "var(--lumo-success-color)");
            } else {
                badge.getStyle().set("background-color", "var(--lumo-error-contrast-color)");
                badge.getStyle().set("color", "var(--lumo-error-color)");
            }
            return badge;
        }).setHeader("Enabled").setFlexGrow(1);
        addActionColumn(); // <-- кнопки Edit/Delete (см. раздел 3)
        add(grid);
    }
    /** Обновляет Span одного тега данными из реестра. */
    private void applyValue(Long tagId) {
        Span span = valueSpans.get(tagId);
        if (span == null) return;

        Object value = tagRegistry.getTagValue(tagId)
                .map(TagRegistry.TagValue::getValue).orElse(null);
        String quality = tagRegistry.getTagValue(tagId)
                .map(TagRegistry.TagValue::getQuality).orElse("Unknown");

        String newText = value != null ? String.valueOf(value) : "—";
        String newColor = quality.startsWith("Good") ? "var(--lumo-success-color)"
                : quality.startsWith("Uncertain") ? "var(--lumo-warning-color)"
                : "var(--lumo-error-color)";

        // Обновляем DOM только при изменении — экономим трафик Push
        if (!newText.equals(span.getText())
                || !newColor.equals(span.getElement().getStyle().get("color"))) {
            getUI().ifPresent(ui -> ui.access(() -> {
                span.setText(newText);
                span.getStyle().set("color", newColor);
            }));
        }
    }

    @Override
    protected void onAttach(AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();
        ui.setPollInterval(1000);                 // браузер пингует сервер раз в секунду
        pollRegistration = ui.addPollListener(e ->
                valueSpans.keySet().forEach(this::applyValue));
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


    private void createContextMenu() {
        grid.addContextMenu().addItem("Edit", e -> e.getItem().ifPresent(dialog::edit));
        grid.addContextMenu().addItem("Delete", e -> e.getItem().ifPresent(this::confirmDelete));
    }
    private void addActionColumn() {
        grid.addComponentColumn(tag -> {
            Button editBtn = new Button(new Icon(VaadinIcon.EDIT));
            editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
            editBtn.setTooltipText("Edit");
            editBtn.addClickListener(e -> editTag(tag.getId()));

            Button deleteBtn = new Button(new Icon(VaadinIcon.TRASH));
            deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY,
                    ButtonVariant.LUMO_ERROR);
            deleteBtn.setTooltipText("Delete");
            deleteBtn.addClickListener(e -> confirmDelete(tag));

            HorizontalLayout actions = new HorizontalLayout(editBtn, deleteBtn);
            actions.setSpacing(false);
            actions.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
            return actions;
        }).setHeader("Actions").setFlexGrow(0).setWidth("110px");
    }

    /**
     * Редактирование по id: перечитываем сущность из БД,
     * чтобы не редактировать устаревший detached-объект из Grid
     * (иначе возможен OptimisticLockException из-за @Version).
     */
    private void editTag(Long tagId) {
        tagService.findById(tagId).ifPresentOrElse(
                dialog::edit,
                () -> Notification.show("Tag not found", 3000, Notification.Position.MIDDLE)
        );
    }
    private Span createQualityBadge(Tag tag) {
        String quality = tagRegistry.getTagValue(tag.getId()).map(com.opcproxy.tags.TagRegistry.TagValue::getQuality).orElse("Unknown");

        Span badge = new Span(quality);
        badge.addClassNames(LumoUtility.Padding.Horizontal.SMALL, LumoUtility.Padding.Vertical.XSMALL, LumoUtility.BorderRadius.MEDIUM, LumoUtility.FontSize.XSMALL, LumoUtility.FontWeight.BOLD);

        if (quality.startsWith("Good")) {
            badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
            badge.getStyle().set("color", "var(--lumo-success-color)");
        } else if (quality.startsWith("Uncertain")) {
            badge.getStyle().set("background-color", "var(--lumo-warning-contrast-color)");
            badge.getStyle().set("color", "var(--lumo-warning-color)");
        } else {
            badge.getStyle().set("background-color", "var(--lumo-error-contrast-color)");
            badge.getStyle().set("color", "var(--lumo-error-color)");
        }
        return badge;
    }

    private void confirmDelete(Tag tag) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete Tag");
        dialog.setText("Are you sure you want to delete tag '" + tag.getName() + "'?");
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            tagService.delete(tag.getId());
            refreshGrid();
            Notification.show("Tag deleted", 2000, Notification.Position.BOTTOM_END);
        });
        dialog.open();
    }

    private void refreshGrid() {
        List<Tag> tags = tagService.findAll();
        valueSpans.clear();          // старые Span больше не актуальны
        grid.setItems(tags);
    }
}