package com.opcproxy.ui.views;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.ReadMode;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
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
import com.vaadin.flow.data.validator.StringLengthValidator;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
public class TagDialog extends Dialog {

    private final TagService tagService;
    private final OpcDaConnectionRepository connectionRepository;
    private final Runnable onSaveCallback;

    private final TextField nameField = new TextField("Tag Name");
    private final ComboBox<SourceType> sourceTypeBox = new ComboBox<>("Source Type");
    private final ComboBox<OpcDaConnection> connectionBox = new ComboBox<>("OPC DA Connection");
    private final TextField sourceItemIdField = new TextField("Item ID / Tag Path");
    private final ComboBox<DataType> dataTypeBox = new ComboBox<>("Data Type");
    private final ComboBox<ReadMode> readModeBox = new ComboBox<>("Read Mode");
    private final IntegerField refreshPeriodField = new IntegerField("Refresh Period (ms)");
    private final Checkbox enabledCheckbox = new Checkbox("Enabled");
    private final TextArea descriptionArea = new TextArea("Description");

    private final Binder<Tag> binder = new Binder<>(Tag.class);
    private Tag currentTag;

    public TagDialog(TagService tagService, OpcDaConnectionRepository connectionRepository, Runnable onSaveCallback) {
        this.tagService = tagService;
        this.connectionRepository = connectionRepository;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle("OPC Tag Configuration");
        setCloseOnOutsideClick(false);
        setWidth("600px");

        configureFields();
        configureBinder();
        buildLayout();
    }

    private void configureFields() {
        nameField.setRequired(true);
        nameField.setPlaceholder("e.g., Tank1_Level");

        sourceTypeBox.setItems(SourceType.values());
        sourceTypeBox.setItemLabelGenerator(Enum::name);
        sourceTypeBox.setRequired(true);

        connectionBox.setItems(connectionRepository.findAll());
        connectionBox.setItemLabelGenerator(OpcDaConnection::getName);

        sourceItemIdField.setPlaceholder("e.g., Channel1.Device1.Tag1");

        dataTypeBox.setItems(DataType.values());
        dataTypeBox.setItemLabelGenerator(Enum::name);
        dataTypeBox.setRequired(true);

        readModeBox.setItems(ReadMode.values());
        readModeBox.setItemLabelGenerator(Enum::name);

        refreshPeriodField.setMin(100);
        refreshPeriodField.setStep(100);
        refreshPeriodField.setRequired(true);

        descriptionArea.setMaxLength(500);

        // Логика показа/скрытия полей OPC DA
        sourceTypeBox.addValueChangeListener(e -> {
            boolean isDa = e.getValue() == SourceType.DA;
            connectionBox.setVisible(isDa);
            sourceItemIdField.setVisible(isDa);
            readModeBox.setVisible(isDa);
            refreshPeriodField.setVisible(isDa);
        });
    }

    private void configureBinder() {
        binder.forField(nameField)
                .withValidator(new StringLengthValidator("Name must be 1-255 characters", 1, 255))
                .bind(Tag::getName, Tag::setName);

        binder.bind(sourceTypeBox, Tag::getSourceType, Tag::setSourceType);
        binder.bind(connectionBox, Tag::getConnection, Tag::setConnection);
        binder.bind(sourceItemIdField, Tag::getSourceItemId, Tag::setSourceItemId);
        binder.bind(dataTypeBox, Tag::getDataType, Tag::setDataType);
        binder.bind(readModeBox, Tag::getReadMode, Tag::setReadMode);
        binder.bind(refreshPeriodField, Tag::getRefreshPeriodMs, Tag::setRefreshPeriodMs);
        binder.bind(enabledCheckbox, Tag::getEnabled, Tag::setEnabled);
        binder.bind(descriptionArea, Tag::getDescription, Tag::setDescription);
    }

    private void buildLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        HorizontalLayout row1 = new HorizontalLayout(nameField, sourceTypeBox);
        row1.setWidthFull();
        row1.setFlexGrow(1, nameField, sourceTypeBox);

        HorizontalLayout row2 = new HorizontalLayout(connectionBox, sourceItemIdField);
        row2.setWidthFull();
        row2.setFlexGrow(1, connectionBox, sourceItemIdField);

        HorizontalLayout row3 = new HorizontalLayout(dataTypeBox, readModeBox, refreshPeriodField);
        row3.setWidthFull();
        row3.setFlexGrow(1, dataTypeBox, readModeBox, refreshPeriodField);

        layout.add(row1, row2, row3, enabledCheckbox, descriptionArea);

        Button saveButton = new Button("Save", e -> save());
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
            currentTag.setDataType(DataType.FLOAT);
            currentTag.setReadMode(ReadMode.ASYNC);
            currentTag.setRefreshPeriodMs(1000);
            currentTag.setEnabled(true);
        } else {
            currentTag = tag;
        }
        binder.setBean(currentTag);
        setHeaderTitle(tag == null ? "Add Tag" : "Edit Tag");
        open();
    }

    private void save() {
        if (binder.validate().isOk()) {
            if (currentTag.getId() == null && tagService.existsByName(currentTag.getName())) {
                Notification.show("Tag name must be unique", 3000, Notification.Position.MIDDLE);
                return;
            }
            if (currentTag.getSourceType() == SourceType.DA && currentTag.getConnection() == null) {
                Notification.show("Please select an OPC DA Connection", 3000, Notification.Position.MIDDLE);
                return;
            }

            tagService.save(currentTag);
            onSaveCallback.run();
            close();
            Notification.show("Tag saved successfully", 2000, Notification.Position.BOTTOM_END);
        } else {
            Notification.show("Please fix validation errors", 2000, Notification.Position.MIDDLE);
        }
    }
}