package com.opcproxy.ui.views;

import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.DashboardService;
import com.vaadin.flow.component.AttachEvent;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;

@PageTitle("Dashboard")
@Route(value = "", layout = MainLayout.class) // Пустая строка "" означает корень "/"
@PermitAll
public class DashboardView extends VerticalLayout {

    private final DashboardService dashboardService;

    private final Span opcUaStatus = new Span();
    private final Span totalTags = new Span();
    private final Span goodTags = new Span();
    private final Span badTags = new Span();
    private final Grid<DashboardService.ConnectionStatusDto> connectionGrid = new Grid<>();

    public DashboardView(DashboardService dashboardService) {
        this.dashboardService = dashboardService;
        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setWidthFull();

        createOpcUaStatusCard();
        createTagStatsCard();
        createConnectionsGrid();

        refreshData();
    }

    private void createOpcUaStatusCard() {
        H2 title = new H2("OPC UA Server");
        title.addClassNames(LumoUtility.Margin.Bottom.SMALL);

        opcUaStatus.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.FontWeight.BOLD);

        VerticalLayout card = new VerticalLayout(title, opcUaStatus);
        card.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.Padding.MEDIUM, LumoUtility.BorderRadius.MEDIUM);
        card.setWidthFull();
        add(card);
    }

    private void createTagStatsCard() {
        H2 title = new H2("Tag Statistics");
        title.addClassNames(LumoUtility.Margin.Bottom.SMALL);

        HorizontalLayout stats = new HorizontalLayout();
        stats.setSpacing(true);

        totalTags.setText("Total: 0");
        goodTags.setText("Good: 0");
        goodTags.addClassNames("status-good");
        badTags.setText("Bad: 0");
        badTags.addClassNames("status-bad");

        stats.add(totalTags, goodTags, badTags);

        VerticalLayout card = new VerticalLayout(title, stats);
        card.addClassNames(LumoUtility.Background.CONTRAST_5, LumoUtility.Padding.MEDIUM, LumoUtility.BorderRadius.MEDIUM);
        card.setWidthFull();
        add(card);
    }

    private void createConnectionsGrid() {
        H2 title = new H2("OPC DA Connections");
        title.addClassNames(LumoUtility.Margin.Bottom.SMALL);

        connectionGrid.addColumn(DashboardService.ConnectionStatusDto::name).setHeader("Name");
        connectionGrid.addColumn(DashboardService.ConnectionStatusDto::host).setHeader("Host");
        connectionGrid.addColumn(DashboardService.ConnectionStatusDto::state).setHeader("State");
        connectionGrid.addColumn(DashboardService.ConnectionStatusDto::errorCount).setHeader("Errors");

        connectionGrid.setWidthFull();
        connectionGrid.setHeight("200px");

        add(title, connectionGrid);
    }

    @Override
    protected void onAttach(AttachEvent attachEvent) {
        super.onAttach(attachEvent);
        // Можно добавить UI.poll(1000, e -> refreshData()) для автообновления,
        // но пока сделаем ручное обновление при входе на вкладку
    }

    public void refreshData() {
        // OPC UA Status
        boolean uaRunning = dashboardService.isOpcUaServerRunning();
        opcUaStatus.setText(uaRunning ? "RUNNING (Port " + dashboardService.getOpcUaServerPort() + ")" : "STOPPED");
        opcUaStatus.getStyle().set("color", uaRunning ? "var(--lumo-success-color)" : "var(--lumo-error-color)");

        // Tag Stats
        DashboardService.TagStatsDto stats = dashboardService.getTagStats();
        totalTags.setText("Total: " + stats.total());
        goodTags.setText("Good: " + stats.good());
        badTags.setText("Bad: " + stats.bad());

        // Connections
        connectionGrid.setItems(dashboardService.getConnectionStatuses());
    }
}