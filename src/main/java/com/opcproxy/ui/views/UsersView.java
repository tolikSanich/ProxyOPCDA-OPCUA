package com.opcproxy.ui.views;

import com.opcproxy.persistence.entity.UserAccount;
import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.UserService;
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
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.RolesAllowed;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@PageTitle("User Management")
@Route(value = "users", layout = MainLayout.class)
@RolesAllowed("ADMIN")
public class UsersView extends VerticalLayout {

    private final UserService userService;
    private final Grid<UserAccount> grid = new Grid<>();
    private final UserDialog dialog;

    public UsersView(UserService userService) {
        this.userService = userService;
        this.dialog = new UserDialog(userService, this::refreshGrid);

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setWidthFull();

        createHeader();
        createGrid();
        refreshGrid();
    }

    private void createHeader() {
        H2 title = new H2("User Management");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);

        Button addButton = new Button("Add User", new Icon(VaadinIcon.PLUS_CIRCLE));
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
        grid.setHeight("500px");
        grid.setSelectionMode(Grid.SelectionMode.SINGLE);

        grid.addColumn(UserAccount::getUsername)
                .setHeader("Username")
                .setFlexGrow(2)
                .setSortable(true);

        grid.addColumn(user -> user.getRole().name())
                .setHeader("Role")
                .setFlexGrow(1);

        grid.addComponentColumn(user -> {
                    Span badge = new Span(user.getEnabled() ? "ACTIVE" : "DISABLED");
                    badge.addClassNames(
                            LumoUtility.Padding.Horizontal.SMALL,
                            LumoUtility.Padding.Vertical.XSMALL,
                            LumoUtility.BorderRadius.MEDIUM,
                            LumoUtility.FontSize.XSMALL,
                            LumoUtility.FontWeight.BOLD
                    );
                    if (user.getEnabled()) {
                        badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-success-color)");
                    } else {
                        badge.getStyle().set("background-color", "var(--lumo-error-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-error-color)");
                    }
                    return badge;
                })
                .setHeader("Status")
                .setFlexGrow(1);

        grid.addComponentColumn(this::createActions)
                .setHeader("Actions")
                .setFlexGrow(0)
                .setWidth("150px");

        add(grid);
    }

    private HorizontalLayout createActions(UserAccount user) {
        Button toggleBtn = new Button(user.getEnabled() ? "Disable" : "Enable");
        toggleBtn.addThemeVariants(ButtonVariant.LUMO_SMALL,
                user.getEnabled() ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS);
        toggleBtn.addClickListener(e -> {
            userService.setEnabled(user.getId(), !user.getEnabled());
            refreshGrid();
        });

        Button editBtn = new Button(new Icon(VaadinIcon.EDIT));
        editBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        editBtn.addClickListener(e -> dialog.edit(user));

        Button deleteBtn = new Button(new Icon(VaadinIcon.TRASH));
        deleteBtn.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY, ButtonVariant.LUMO_ERROR);
        deleteBtn.addClickListener(e -> confirmDelete(user));

        HorizontalLayout layout = new HorizontalLayout(toggleBtn, editBtn, deleteBtn);
        layout.setSpacing(false);
        layout.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        return layout;
    }

    private void confirmDelete(UserAccount user) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete User");
        dialog.setText("Are you sure you want to delete user '" + user.getUsername() + "'?");
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            try {
                userService.delete(user.getId());
                refreshGrid();
                Notification.show("User deleted", 2000, Notification.Position.BOTTOM_END);
            } catch (Exception ex) {
                Notification.show("Error: " + ex.getMessage(), 3000, Notification.Position.MIDDLE);
            }
        });
        dialog.open();
    }

    private void refreshGrid() {
        List<UserAccount> users = userService.findAll();
        grid.setItems(users);
    }
}