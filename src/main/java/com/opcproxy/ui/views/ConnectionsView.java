package com.opcproxy.ui.views;

import com.opcproxy.opcda.ConnectionState;
import com.opcproxy.opcda.OpcDaClient;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.ConnectionService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.confirmdialog.ConfirmDialog;
import com.vaadin.flow.component.contextmenu.MenuItem;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.grid.contextmenu.GridContextMenu;
import com.vaadin.flow.component.grid.contextmenu.GridMenuItem;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Hr;
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
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

@Slf4j
@PageTitle("OPC DA Connections")
@Route(value = "/connections", layout = MainLayout.class)
@PermitAll
public class ConnectionsView extends VerticalLayout {

    private final ConnectionService connectionService;
    private final Grid<OpcDaConnection> grid = new Grid<>();
    private final ConnectionsDialog dialog;

    public ConnectionsView(ConnectionService connectionService) {
        this.connectionService = connectionService;
        this.dialog = new ConnectionsDialog(connectionService, this::refreshGrid);

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setWidthFull();

        createHeader();
        createGrid();
        createContextMenu();

        refreshGrid();
    }

    private void createHeader() {
        H2 title = new H2("OPC DA Connections");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);

        Button addButton = new Button("Add Connection", new Icon(VaadinIcon.PLUS_CIRCLE));
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

        // Колонка имени
        grid.addColumn(OpcDaConnection::getName)
                .setHeader("Name")
                .setFlexGrow(2)
                .setSortable(true);

        // Колонка хоста
        grid.addColumn(OpcDaConnection::getHost)
                .setHeader("Host")
                .setFlexGrow(1);

        // Колонка ProgID
        grid.addColumn(OpcDaConnection::getProgIdOrClsid)
                .setHeader("ProgID / CLSID")
                .setFlexGrow(1);

        // Колонка статуса с цветовой индикацией
        grid.addComponentColumn(this::createStatusBadge)
                .setHeader("Status")
                .setFlexGrow(1);

        // Колонка режима чтения
        grid.addColumn(conn -> conn.getDefaultReadMode().name())
                .setHeader("Mode")
                .setFlexGrow(1);

        // Колонка интервала опроса
        grid.addColumn(conn -> conn.getDefaultRefreshPeriodMs() + " ms")
                .setHeader("Polling")
                .setFlexGrow(1);

        // Колонка количества ошибок
        grid.addColumn(conn -> {
                    int errors = connectionService.getErrorCount(conn.getId());
                    return errors > 0 ? String.valueOf(errors) : "-";
                })
                .setHeader("Errors")
                .setFlexGrow(1);

        // Колонка enabled
        grid.addComponentColumn(conn -> {
                    Span badge = new Span(conn.getEnabled() ? "YES" : "NO");
                    badge.addClassNames(
                            LumoUtility.Padding.Horizontal.SMALL,
                            LumoUtility.Padding.Vertical.XSMALL,
                            LumoUtility.BorderRadius.MEDIUM,
                            LumoUtility.FontSize.XSMALL,
                            LumoUtility.FontWeight.BOLD
                    );
                    if (conn.getEnabled()) {
                        badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-success-color)");
                    } else {
                        badge.getStyle().set("background-color", "var(--lumo-error-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-error-color)");
                    }
                    return badge;
                })
                .setHeader("Enabled")
                .setFlexGrow(1);

        add(grid);
    }

    private void createContextMenu() {
        GridContextMenu<OpcDaConnection> contextMenu = grid.addContextMenu();

        GridMenuItem<OpcDaConnection> editItem = contextMenu.addItem("Edit");
        editItem.addMenuItemClickListener(opcDaConnectionGridContextMenuItemClickEvent ->
                opcDaConnectionGridContextMenuItemClickEvent.getItem()
                        .ifPresent(this::editConnection));

        GridMenuItem<OpcDaConnection> testItem = contextMenu.addItem("Test Connection");
        testItem.addMenuItemClickListener(
                e ->
                        e.getItem().ifPresent(this::testConnection));

        GridMenuItem<OpcDaConnection> reconnectItem = contextMenu.addItem("Reconnect");
        reconnectItem.addMenuItemClickListener(e -> e.getItem().ifPresent(this::reconnect));
        contextMenu.addComponent(new Hr());

        GridMenuItem<OpcDaConnection> deleteItem = contextMenu.addItem("Delete");
        deleteItem.addMenuItemClickListener(
                e -> e.getItem()
                        .ifPresent(this::confirmDelete));
    }

    private Span createStatusBadge(OpcDaConnection connection) {
        ConnectionState state = connectionService.getStatus(connection.getId());
        Span badge = new Span(state.name());
        badge.addClassNames(
                LumoUtility.Padding.Horizontal.SMALL,
                LumoUtility.Padding.Vertical.XSMALL,
                LumoUtility.BorderRadius.MEDIUM,
                LumoUtility.FontSize.XSMALL,
                LumoUtility.FontWeight.BOLD
        );

        switch (state) {
            case CONNECTED:
                badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
                badge.getStyle().set("color", "var(--lumo-success-color)");
                break;
            case CONNECTING:
            case RECONNECTING:
                badge.getStyle().set("background-color", "var(--lumo-warning-contrast-color)");
                badge.getStyle().set("color", "var(--lumo-warning-color)");
                break;
            case FAILED:
            case DISCONNECTED:
                badge.getStyle().set("background-color", "var(--lumo-error-contrast-color)");
                badge.getStyle().set("color", "var(--lumo-error-color)");
                break;
        }

        return badge;
    }

    private void editConnection(OpcDaConnection connection) {
        dialog.edit(connection);
    }

    public String testConnection(OpcDaConnection connection) {
        OpcDaClient testClient = new OpcDaClient(connection);
        try {
            testClient.connect();
            testClient.disconnect();
            return "Подключение успешно установлено";
        } catch (Exception e) {
            log.error("Test connection failed for: {}", connection.getName(), e);

            // Дружелюбное сообщение об ошибке на основе стека j-Interop
            String errorMsg = e.getMessage();
            if (errorMsg != null && (errorMsg.contains("0xC0000001") || errorMsg.contains("SmbException") || errorMsg.contains("Connection reset"))) {
                return "Ошибка DCOM/SMB: Проверьте, что указан IP-адрес (не localhost), верны логин/пароль Windows, и в dcomcnfg настроены права 'Launch and Activation Permissions'.";
            } else if (errorMsg != null && errorMsg.contains("Class not registered")) {
                return "Ошибка: ProgID или CLSID не найден в реестре Windows. Проверьте правильность написания.";
            }

            return "Ошибка подключения: " + e.getMessage();
        }
    }

    private void reconnect(OpcDaConnection connection) {
        connectionService.reconnect(connection.getId());
        Notification.show("Reconnecting to " + connection.getName(), 2000, Notification.Position.BOTTOM_END);
        // Обновляем статус через секунду
        getUI().ifPresent(ui -> ui.accessLater(this::refreshGrid, null));
    }

    private void confirmDelete(OpcDaConnection connection) {
        ConfirmDialog dialog = new ConfirmDialog();
        dialog.setHeader("Delete Connection");
        dialog.setText("Are you sure you want to delete connection '" + connection.getName() + "'?");
        dialog.setConfirmText("Delete");
        dialog.setConfirmButtonTheme("error primary");
        dialog.addConfirmListener(e -> {
            connectionService.delete(connection.getId());
            refreshGrid();
            Notification.show("Connection deleted", 2000, Notification.Position.BOTTOM_END);
        });
        dialog.open();
    }

    private void refreshGrid() {
        List<OpcDaConnection> connections = connectionService.findAll();
        grid.setItems(connections);
    }
}