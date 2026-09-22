package com.opcproxy.ui.views;

import com.opcproxy.calc.CalcDependencyValidator;
import com.opcproxy.persistence.entity.IntervalProfile;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.ReadMode;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.IntervalProfileRepository;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.ui.services.IntervalProfileService;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.binder.ValidationException;
import com.vaadin.flow.data.binder.ValidationResult;
import com.vaadin.flow.data.validator.StringLengthValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

/**
 * Диалог создания/редактирования тега.
 * Динамическая форма: поля зависят от sourceType.
 *   DA   -> connection + sourceItemId (+ readMode, периоды)
 *   CALC -> expression (формула SpEL) + валидация зависимостей
 */
@Slf4j
public class TagDialog extends Dialog {

    private final TagService tagService;
    private final OpcDaConnectionRepository connectionRepository;
    private final CalcDependencyValidator calcValidator;
    private final Runnable onSaveCallback;
    private final TextField mqttDeadbandField = new TextField("MQTT Deadband");

    private final TextField nameField = new TextField("Tag Name");
    private final ComboBox<SourceType> sourceTypeBox = new ComboBox<>("Source Type");
    private final ComboBox<OpcDaConnection> connectionBox = new ComboBox<>("Connection");
    private final TextField sourceItemIdField = new TextField("Source ItemID");
    private final TextArea expressionArea = new TextArea("Expression (SpEL)");
    private final ComboBox<DataType> dataTypeBox = new ComboBox<>("Data Type");
    private final ComboBox<ReadMode> readModeBox = new ComboBox<>("Read Mode");
    private final IntegerField refreshPeriodField = new IntegerField("Refresh Period (ms)");
    private final Checkbox enabledCheckbox = new Checkbox("Enabled");
    private final Checkbox publishMqttCheckbox = new Checkbox("Publish to MQTT");
    private final TextArea descriptionArea = new TextArea("Description");
    private final ComboBox<IntervalProfile> profileBox = new ComboBox<>("UA Interval Profile");

    private final IntervalProfileRepository profileRepository;
    private final Binder<Tag> binder = new Binder<>(Tag.class);
    private Tag currentTag;

    public TagDialog(TagService tagService,
                     OpcDaConnectionRepository connectionRepository,
                     Runnable onSaveCallback, IntervalProfileRepository profileRepository) {
        this(tagService, connectionRepository, null, onSaveCallback, profileRepository);
    }

    /** Конструктор с валидатором (рекомендуемый; null-валидатор => проверка формулы отключена). */
    public TagDialog(TagService tagService,
                     OpcDaConnectionRepository connectionRepository,
                     CalcDependencyValidator calcValidator,
                     Runnable onSaveCallback, IntervalProfileRepository profileRepository) {
        this.tagService = tagService;
        this.connectionRepository = connectionRepository;
        this.calcValidator = calcValidator;
        this.onSaveCallback = onSaveCallback;
        this.profileRepository = profileRepository;

        setHeaderTitle("Tag");
        setCloseOnOutsideClick(false);
        setWidth("600px");

        configureFields();
        configureBinder();
        buildLayout();
    }

    // ------------------------------------------------------------------

    private void configureFields() {
        nameField.setRequired(true);
        nameField.setPlaceholder("e.g. Tank1_Level");

        sourceTypeBox.setItems(SourceType.values());
        sourceTypeBox.setItemLabelGenerator(Enum::name);
        sourceTypeBox.setRequired(true);
        // ГЛАВНОЕ: переключение видимости полей по типу источника
        sourceTypeBox.addValueChangeListener(e -> updateFieldVisibility(e.getValue()));

        connectionBox.setItems(connectionRepository.findAll());
        connectionBox.setItemLabelGenerator(OpcDaConnection::getName);
        connectionBox.setWidthFull();

        sourceItemIdField.setPlaceholder("e.g. Test/Float");
        sourceItemIdField.setWidthFull();

        expressionArea.setPlaceholder("#Tank1_Level > 80 ? 'HIGH' : 'OK'");
        expressionArea.setMinHeight("90px");
        expressionArea.setWidthFull();
        expressionArea.setHelperText(
                "SpEL: #TagName — ссылки, T(java.lang.Math) — функции. Валидация при сохранении.");

        dataTypeBox.setItems(DataType.values());
        dataTypeBox.setItemLabelGenerator(Enum::name);

        readModeBox.setItems(ReadMode.values());
        readModeBox.setItemLabelGenerator(Enum::name);

        profileBox.setItems(profileRepository.findAll());
        profileBox.setItemLabelGenerator(IntervalProfile::getName);
        profileBox.setAllowCustomValue(false);

        refreshPeriodField.setMin(100);
        refreshPeriodField.setStep(100);
        refreshPeriodField.setValue(1000);
        mqttDeadbandField.setPlaceholder("e.g. 0.5 (0 = публиковать все изменения)");
        mqttDeadbandField.setHelperText("Опубликовать при изменении больше чем на значение");
        descriptionArea.setMaxLength(800);
        descriptionArea.setMinHeight("70px");
        publishMqttCheckbox.addValueChangeListener(e ->
                mqttDeadbandField.setVisible(Boolean.TRUE.equals(e.getValue())));
    }

    /** Динамическая видимость: DA -> connection+ItemID; CALC -> формула. */
    private void updateFieldVisibility(SourceType type) {
        boolean isDA = type == SourceType.DA;
        boolean isCALC = type == SourceType.CALC;

        connectionBox.setVisible(isDA);
        sourceItemIdField.setVisible(isDA);
        readModeBox.setVisible(isDA);
        expressionArea.setVisible(isCALC);
    }

    private void configureBinder() {
        binder.forField(nameField)
                .withValidator(new StringLengthValidator("Name must be 1-255 characters", 1, 255))
                .bind(Tag::getName, Tag::setName);

        binder.forField(sourceTypeBox)
                .withValidator(v -> v != null, "Выберите тип источника")
                .bind(Tag::getSourceType, Tag::setSourceType);

        // DA-поля: обязательны только для DA (проверка в валидаторе, а не глобально)
        binder.forField(connectionBox)
                .withValidator(this::validateConnectionForDA)
                .bind(Tag::getConnection, Tag::setConnection);

        binder.forField(sourceItemIdField)
                .withValidator(this::validateItemIdForDA)
                .bind(Tag::getSourceItemId, Tag::setSourceItemId);

        // Формула: валидация синтаксиса + зависимостей (для CALC)
        binder.forField(expressionArea)
                .withValidator(this::validateExpression)
                .bind(Tag::getExpression, Tag::setExpression);

        binder.forField(profileBox)
                .bind(Tag::getIntervalProfile, Tag::setIntervalProfile);

        binder.forField(dataTypeBox)
                .withValidator(v -> v != null, "Выберите тип данных")
                .bind(Tag::getDataType, Tag::setDataType);

        binder.forField(readModeBox)
                .bind(Tag::getReadMode, Tag::setReadMode);

        binder.forField(refreshPeriodField)
                .withConverter(v -> v == null ? 1000 : v, v -> v)
                .bind(Tag::getRefreshPeriodMs, Tag::setRefreshPeriodMs);
        binder.forField(mqttDeadbandField)
                .withConverter(
                        s -> (s == null || s.isBlank()) ? null : s.trim().replace(',', '.'),
                        Object::toString)
                .withValidator(s -> {
                    if (s == null || s.isBlank()) return true;
                    try { return Float.parseFloat(s) >= 0; }
                    catch (NumberFormatException e) { return false; }
                }, "Число >= 0 (например 0.5)")
                .withConverter(
                        s -> (s == null || s.isBlank()) ? null : Float.parseFloat(s),
                        f -> f == null ? "" : String.valueOf(f))
                .bind(Tag::getMqttDeadband, Tag::setMqttDeadband);

        binder.bind(enabledCheckbox, Tag::getEnabled, Tag::setEnabled);
        binder.bind(publishMqttCheckbox, Tag::getPublishMqtt, Tag::setPublishMqtt);
        binder.bind(descriptionArea, Tag::getDescription, Tag::setDescription);
    }

    private ValidationResult validateConnectionForDA(OpcDaConnection conn,
                                                     com.vaadin.flow.data.binder.ValueContext ctx) {
        SourceType st = sourceTypeBox.getValue();
        if (st == SourceType.DA && conn == null) {
            return ValidationResult.error("Для DA-тега обязательно подключение");
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateItemIdForDA(String itemId,
                                                 com.vaadin.flow.data.binder.ValueContext ctx) {
        SourceType st = sourceTypeBox.getValue();
        if (st == SourceType.DA && (itemId == null || itemId.isBlank())) {
            return ValidationResult.error("Для DA-тега обязателен ItemID");
        }
        return ValidationResult.ok();
    }

    private ValidationResult validateExpression(String expr,
                                                com.vaadin.flow.data.binder.ValueContext ctx) {
        SourceType st = sourceTypeBox.getValue();
        if (st != SourceType.CALC) {
            return ValidationResult.ok();   // для DA формула не нужна
        }
        if (expr == null || expr.isBlank()) {
            return ValidationResult.error("Для CALC-тега обязательна формула");
        }
        if (calcValidator == null) {
            return ValidationResult.ok();   // движок ещё не собран — только UI-проверка
        }
        // Полная проверка: синтаксис, ссылки, циклы, глубина
        Tag probe = new Tag();
        probe.setName(nameField.getValue());
        probe.setSourceType(SourceType.CALC);
        probe.setExpression(expr);
        var result = calcValidator.validate(probe);
        if (!result.ok()) {
            return ValidationResult.error(String.join("; ", result.errors()));
        }
        return ValidationResult.ok();
    }

    private void buildLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        HorizontalLayout row1 = new HorizontalLayout(nameField, sourceTypeBox);
        row1.setWidthFull();
        row1.setFlexGrow(1, nameField, sourceTypeBox);

        connectionBox.setWidthFull();
        sourceItemIdField.setWidthFull();
        expressionArea.setWidthFull();

        HorizontalLayout row2 = new HorizontalLayout(dataTypeBox, readModeBox, refreshPeriodField);
        row2.setWidthFull();
        row2.setFlexGrow(1, dataTypeBox, readModeBox, refreshPeriodField);

        HorizontalLayout row3 = new HorizontalLayout(enabledCheckbox, publishMqttCheckbox, mqttDeadbandField);
        row3.setWidthFull();
        row3.setFlexGrow(1, enabledCheckbox, publishMqttCheckbox, mqttDeadbandField);

        descriptionArea.setWidthFull();

        layout.add(row1, connectionBox, sourceItemIdField, expressionArea,
                row2, profileBox, row3, descriptionArea);

        Button saveButton = new Button("Save", e -> {
            try {
                save();
            } catch (ValidationException ex) {
                throw new RuntimeException(ex);
            }
        });
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button("Cancel", e -> close());
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        HorizontalLayout buttons = new HorizontalLayout(cancelButton, saveButton);
        buttons.setWidthFull();
        buttons.setJustifyContentMode(FlexComponent.JustifyContentMode.END);

        layout.add(buttons);
        add(layout);
    }

    public void edit(Tag tag) {
        if (tag == null) {
            currentTag = new Tag();
            currentTag.setSourceType(SourceType.DA);
            currentTag.setReadMode(ReadMode.ASYNC);
            currentTag.setRefreshPeriodMs(1000);
            currentTag.setEnabled(true);
            currentTag.setPublishMqtt(false);
        } else {
            currentTag = tag;
        }
        binder.setBean(currentTag);
        updateFieldVisibility(currentTag.getSourceType());
        setHeaderTitle(tag == null || tag.getId() == null ? "Add Tag" : "Edit Tag");
        mqttDeadbandField.setVisible(Boolean.TRUE.equals(currentTag.getPublishMqtt()));
        open();
    }

    private void save() throws ValidationException {
        if (!binder.validate().isOk()) {
            Notification.show("Please fix validation errors", 3000, Notification.Position.MIDDLE);
            return;
        }
        binder.writeBean(currentTag);

        if (currentTag.getId() == null && tagService.existsByName(currentTag.getName())) {
            Notification.show("Tag name must be unique", 3000, Notification.Position.MIDDLE);
            return;
        }

        tagService.save(currentTag);
        onSaveCallback.run();
        close();
        Notification.show("Tag saved", 2000, Notification.Position.BOTTOM_END);
    }
}