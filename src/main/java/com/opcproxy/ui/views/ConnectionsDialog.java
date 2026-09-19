package com.opcproxy.ui.views;

import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.enums.ReadMode;
import com.opcproxy.ui.services.ConnectionService;
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
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextArea;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.validator.StringLengthValidator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class ConnectionsDialog extends Dialog {

    private final ConnectionService connectionService;
    private final Runnable onSaveCallback;

    private final TextField nameField = new TextField("Connection Name");
    private final TextField hostField = new TextField("Hostname / IP");
    private final TextField progIdField = new TextField("ProgID or CLSID");
    private final TextField domainField = new TextField("Domain (optional)");
    private final TextField usernameField = new TextField("Username");
    private final PasswordField passwordField = new PasswordField("Password");
    private final ComboBox<ReadMode> readModeBox = new ComboBox<>("Default Read Mode");
    private final IntegerField refreshPeriodField = new IntegerField("Refresh Period (ms)");
    private final IntegerField reconnectIntervalField = new IntegerField("Reconnect Interval (ms)");
    private final Checkbox enabledCheckbox = new Checkbox("Enabled");
    private final TextArea descriptionArea = new TextArea("Description");

    private final Binder<OpcDaConnection> binder = new Binder<>(OpcDaConnection.class);
    private OpcDaConnection currentConnection;

    public ConnectionsDialog(ConnectionService connectionService, Runnable onSaveCallback) {
        this.connectionService = connectionService;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle("OPC DA Connection");
        setCloseOnOutsideClick(false);
        setWidth("550px");

        configureFields();
        configureBinder();
        buildLayout();
    }

    private void configureFields() {
        nameField.setRequired(true);
        nameField.setPlaceholder("e.g., PLC_Server_1");

        hostField.setRequired(true);
        hostField.setPlaceholder("e.g., 192.168.1.100 or Home-PC");

        progIdField.setRequired(true);
        progIdField.setPlaceholder("e.g., OPC.SimaticNET or {CAE8D0E1-...}");

        domainField.setPlaceholder("e.g., WORKGROUP or leave empty for local");

        readModeBox.setItems(ReadMode.values());
        readModeBox.setItemLabelGenerator(Enum::name);

        refreshPeriodField.setMin(100);
        refreshPeriodField.setStep(100);

        reconnectIntervalField.setMin(1000);
        reconnectIntervalField.setStep(1000);

        descriptionArea.setMaxLength(500);
    }

    private void configureBinder() {
        binder.forField(nameField)
                .withValidator(new StringLengthValidator("Name must be 1-255 characters", 1, 255))
                .bind(OpcDaConnection::getName, OpcDaConnection::setName);

        binder.forField(hostField)
                .withValidator(new StringLengthValidator("Hostname is required", 1, 255))
                .bind(OpcDaConnection::getHost, OpcDaConnection::setHost);

        binder.forField(progIdField)
                .withValidator(new StringLengthValidator("ProgID/CLSID is required", 1, 255))
                .bind(OpcDaConnection::getProgIdOrClsid, OpcDaConnection::setProgIdOrClsid);

        binder.bind(domainField, OpcDaConnection::getDomain, OpcDaConnection::setDomain);
        binder.bind(usernameField, OpcDaConnection::getUsername, OpcDaConnection::setUsername);
        binder.bind(readModeBox, OpcDaConnection::getDefaultReadMode, OpcDaConnection::setDefaultReadMode);
        binder.bind(refreshPeriodField, OpcDaConnection::getDefaultRefreshPeriodMs, OpcDaConnection::setDefaultRefreshPeriodMs);
        binder.bind(reconnectIntervalField, OpcDaConnection::getReconnectIntervalMs, OpcDaConnection::setReconnectIntervalMs);
        binder.bind(enabledCheckbox, OpcDaConnection::getEnabled, OpcDaConnection::setEnabled);
        binder.bind(descriptionArea, OpcDaConnection::getDescription, OpcDaConnection::setDescription);
    }

    private void buildLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        // Строка 1: Имя и Хост
        HorizontalLayout row1 = new HorizontalLayout(nameField, hostField);
        row1.setWidthFull();
        row1.setFlexGrow(1, nameField, hostField);

        // Строка 2: ProgID / CLSID (на всю ширину)
        progIdField.setWidthFull();

        // Строка 3: Домен, Пользователь, Пароль
        HorizontalLayout row2 = new HorizontalLayout(domainField, usernameField, passwordField);
        row2.setWidthFull();
        passwordField.clear();
        passwordField.setPlaceholder("Пусто = не менять пароль");
        row2.setFlexGrow(1, domainField, usernameField, passwordField);

        // Строка 4: Режим, Интервалы
        HorizontalLayout row3 = new HorizontalLayout(readModeBox, refreshPeriodField, reconnectIntervalField);
        row3.setWidthFull();
        row3.setFlexGrow(1, readModeBox, refreshPeriodField, reconnectIntervalField);

        // Строка 5: Enabled и Описание
        enabledCheckbox.setWidthFull();
        descriptionArea.setWidthFull();
        descriptionArea.setMinHeight("80px");

        layout.add(row1, progIdField, row2, row3, enabledCheckbox, descriptionArea);

        // Кнопки
        Button saveButton = new Button("Save", e -> save());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);

        Button cancelButton = new Button("Cancel", e -> close());
        cancelButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);

        Button testButton = new Button("Test Connection", e -> testConnection());
        testButton.addThemeVariants(ButtonVariant.LUMO_CONTRAST);

        HorizontalLayout buttons = new HorizontalLayout(testButton, cancelButton, saveButton);
        buttons.setWidthFull();
        buttons.setJustifyContentMode(FlexComponent.JustifyContentMode.BETWEEN);
        buttons.expand(saveButton);

        layout.add(buttons);
        add(layout);
    }

    public void edit(OpcDaConnection connection) {
        if (connection == null) {
            currentConnection = new OpcDaConnection();
            currentConnection.setDefaultReadMode(ReadMode.ASYNC);
            currentConnection.setDefaultRefreshPeriodMs(1000);
            currentConnection.setReconnectIntervalMs(5000);
            currentConnection.setEnabled(true);
            currentConnection.setDomain(""); // Явная инициализация
        } else {
            currentConnection = connection;
        }

        binder.setBean(currentConnection);
        passwordField.clear();                                  // всегда чистое поле
        passwordField.setPlaceholder("Пусто = не менять пароль");
        setHeaderTitle(connection == null ? "Add Connection" : "Edit Connection");
        open();
    }

    private void save() {
        if (binder.validate().isOk()) {
            // В save():
            if (passwordField.getValue() == null || passwordField.getValue().isBlank()) {
                // НЕ трогаем passwordEncrypted — save() скопирует старое из БД (логика «empty = keep»)
            } else {
                currentConnection.setPasswordEncrypted(passwordField.getValue());  // plaintext → save() зашифрует
            }
            if (currentConnection.getId() == null && connectionService.existsByName(currentConnection.getName())) {
                Notification.show("Connection name must be unique", 3000, Notification.Position.MIDDLE);
                return;
            }

            connectionService.save(currentConnection);
            onSaveCallback.run();
            close();
            Notification.show("Connection saved successfully", 2000, Notification.Position.BOTTOM_END);
        } else {
            Notification.show("Please fix validation errors", 2000, Notification.Position.MIDDLE);
        }
    }

    private void testConnection() {
        if (binder.validate().isOk()) {
            binder.writeBeanIfValid(currentConnection);
            // Тестируем С ТЕМ паролем, который реально будет сохранён:
            if (passwordField.getValue() == null || passwordField.getValue().isBlank()) {
                // пусто = тест со старым паролем из БД
                connectionService.findById(currentConnection.getId())
                        .ifPresent(old -> currentConnection.setPasswordEncrypted(old.getPasswordEncrypted()));
            } else {
                currentConnection.setPasswordEncrypted(passwordField.getValue()); // plaintext, decrypt сам разберётся
                Notification.show("Please fill required fields before testing", 2000, Notification.Position.MIDDLE);
            }
        }
        String result = connectionService.testConnection(currentConnection);
        Notification.show(result, 4000, Notification.Position.MIDDLE);
    }
}