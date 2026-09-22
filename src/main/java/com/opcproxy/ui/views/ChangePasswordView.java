// src/main/java/com/opcproxy/ui/views/ChangePasswordView.java
package com.opcproxy.ui.views;

import com.opcproxy.ui.services.UserService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.formlayout.FormLayout;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.PasswordField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.spring.security.VaadinAwareSecurityContextHolderStrategy;
import com.vaadin.flow.theme.lumo.LumoUtility;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@PageTitle("Смена пароля")
@Route("change-password")
@AnonymousAllowed // Разрешаем доступ, но логика внутри проверит аутентификацию
public class ChangePasswordView extends VerticalLayout {

    private final UserService userService;

    private final PasswordField oldPasswordField = new PasswordField("Текущий пароль");
    private final PasswordField newPasswordField = new PasswordField("Новый пароль");
    private final PasswordField confirmPasswordField = new PasswordField("Подтвердите новый пароль");

    public ChangePasswordView(UserService userService) {
        this.userService = userService;
        setAlignItems(Alignment.CENTER);
        setJustifyContentMode(JustifyContentMode.CENTER);
        setSizeFull();
        addClassNames(LumoUtility.Padding.MEDIUM);

        H2 title = new H2("Требуется смена пароля");
        title.addClassNames(LumoUtility.Margin.Bottom.MEDIUM);

        FormLayout form = new FormLayout(oldPasswordField, newPasswordField, confirmPasswordField);
        form.setMaxWidth("400px");
        form.setWidthFull();

        Button saveButton = new Button("Сохранить и войти", e -> handleChangePassword());
        saveButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveButton.setWidthFull();

        Button logoutButton = new Button("Выйти", e -> {
            (new VaadinAwareSecurityContextHolderStrategy()).clearContext();
            getUI().ifPresent(ui -> ui.getPage().setLocation("/login"));
        });
        logoutButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        logoutButton.setWidthFull();

        add(title, form, saveButton, logoutButton);
    }

    private void handleChangePassword() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getName())) {
            getUI().ifPresent(ui -> ui.getPage().setLocation("/login"));
            return;
        }

        String username = auth.getName();
        String oldPass = oldPasswordField.getValue();
        String newPass = newPasswordField.getValue();
        String confirmPass = confirmPasswordField.getValue();

        if (!newPass.equals(confirmPass)) {
            Notification.show("Новые пароли не совпадают", 3000, Notification.Position.MIDDLE);
            return;
        }

        try {
            userService.changePassword(username, oldPass, newPass);
            Notification.show("Пароль успешно изменен! Перенаправление...", 2000, Notification.Position.BOTTOM_END);
            getUI().ifPresent(ui -> ui.getPage().setLocation("/")); // Редирект на главную
        } catch (IllegalArgumentException e) {
            Notification.show("Ошибка: " + e.getMessage(), 4000, Notification.Position.MIDDLE);
        }
    }
}