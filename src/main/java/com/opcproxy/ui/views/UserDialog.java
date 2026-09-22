package com.opcproxy.ui.views;

import com.opcproxy.persistence.entity.UserAccount;
import com.opcproxy.persistence.enums.UserRole;
import com.opcproxy.ui.services.UserService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.binder.Binder;
import com.vaadin.flow.data.validator.StringLengthValidator;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class UserDialog extends Dialog {

    private final UserService userService;
    private final Runnable onSaveCallback;

    private final TextField usernameField = new TextField("Username");
    private final PasswordField passwordField = new PasswordField("Password");
    private final ComboBox<UserRole> roleBox = new ComboBox<>("Role");
    private final Checkbox enabledCheckbox = new Checkbox("Enabled");

    private final Binder<UserAccount> binder = new Binder<>(UserAccount.class);
    private UserAccount currentUser;

    public UserDialog(UserService userService, Runnable onSaveCallback) {
        this.userService = userService;
        this.onSaveCallback = onSaveCallback;

        setHeaderTitle("User Account");
        setCloseOnOutsideClick(false);
        setWidth("450px");

        configureFields();
        configureBinder();
        buildLayout();
    }

    private void configureFields() {
        usernameField.setRequired(true);
        usernameField.setPlaceholder("e.g., admin, operator");

        passwordField.setRequired(true);
        passwordField.setPlaceholder("Min 6 characters");

        roleBox.setItems(UserRole.values());
        roleBox.setItemLabelGenerator(Enum::name);
        roleBox.setRequired(true);

        enabledCheckbox.setValue(true);
    }

    private void configureBinder() {
        binder.forField(usernameField)
                .withValidator(new StringLengthValidator("Username must be 3-50 characters", 3, 50))
                .bind(UserAccount::getUsername, UserAccount::setUsername);

        // ВАЖНО: НЕ биндим passwordField к entity, обрабатываем отдельно
        binder.forField(roleBox)
                .withValidator(v -> v != null, "Role is required")
                .bind(UserAccount::getRole, UserAccount::setRole);

        binder.bind(enabledCheckbox, UserAccount::getEnabled, UserAccount::setEnabled);
    }

    private void buildLayout() {
        VerticalLayout layout = new VerticalLayout();
        layout.setPadding(false);
        layout.setSpacing(true);

        usernameField.setWidthFull();
        passwordField.setWidthFull();
        roleBox.setWidthFull();
        enabledCheckbox.setWidthFull();

        layout.add(usernameField, passwordField, roleBox, enabledCheckbox);

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

    public void edit(UserAccount user) {
        if (user == null) {
            currentUser = new UserAccount();
            currentUser.setRole(UserRole.USER);
            currentUser.setEnabled(true);
        } else {
            currentUser = user;
        }

        binder.setBean(currentUser);
        passwordField.clear();
        passwordField.setPlaceholder(user == null ? "Enter password" : "Leave empty to keep current");
        setHeaderTitle(user == null ? "Add User" : "Edit User");
        open();
    }

    private void save() {
        // Валидация полей
        if (!binder.validate().isOk()) {
            Notification.show("Please fix validation errors", 2000, Notification.Position.MIDDLE);
            return;
        }

        // Валидация пароля
        String rawPassword = passwordField.getValue();
        boolean isNewUser = currentUser.getId() == null;

        if (isNewUser && (rawPassword == null || rawPassword.length() < 6)) {
            Notification.show("Password must be at least 6 characters for new user", 3000, Notification.Position.MIDDLE);
            return;
        }

        // Применяем значения из формы к entity
        binder.writeBeanIfValid(currentUser);

        try {
            // Сохраняем с явной передачей пароля
            userService.save(currentUser, rawPassword);
            onSaveCallback.run();
            close();
            Notification.show("User saved successfully", 2000, Notification.Position.BOTTOM_END);
        } catch (Exception e) {
            log.error("Failed to save user", e);
            Notification.show("Error: " + e.getMessage(), 3000, Notification.Position.MIDDLE);
        }
    }
}