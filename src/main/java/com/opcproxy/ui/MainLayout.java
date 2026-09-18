package com.opcproxy.ui;

import com.opcproxy.ui.views.*;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;

@Layout
@PermitAll
public class MainLayout extends AppLayout implements RouterLayout {

    public MainLayout() {
        createHeader();
        createDrawer();
    }

    private void createHeader() {
        H1 logo = new H1("OPC DA-UA Proxy");
        logo.addClassNames(LumoUtility.FontSize.LARGE, LumoUtility.Margin.MEDIUM);

        HorizontalLayout header = new HorizontalLayout(logo);
        header.setDefaultVerticalComponentAlignment(FlexComponent.Alignment.CENTER);
        header.setWidthFull();
        header.addClassNames(LumoUtility.Padding.Vertical.NONE, LumoUtility.Padding.Horizontal.MEDIUM);

        addToNavbar(header);
    }

    private void createDrawer() {
        VerticalLayout drawerLayout = new VerticalLayout();
        drawerLayout.addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);

        SideNav nav = new SideNav();
        nav.addItem(new SideNavItem("Dashboard", DashboardView.class));
        // В методе createDrawer() добавьте:
        nav.addItem(new SideNavItem("Connections", ConnectionsView.class));
        // В следующих этапах добавим сюда остальные вьюшки:
        // nav.addItem(new SideNavItem("Connections", ConnectionsView.class));
        // nav.addItem(new SideNavItem("Tags", TagsView.class));
        // Внутри метода createDrawer() класса MainLayout.java
        nav.addItem(new SideNavItem("Tags", TagsView.class));
        nav.addItem(new SideNavItem("Servers & Tags", MasterDetailView.class));
        nav.addItem(new SideNavItem("Import/Export", ImportExportView.class));
        drawerLayout.add(nav);
        addToDrawer(drawerLayout);
    }
}