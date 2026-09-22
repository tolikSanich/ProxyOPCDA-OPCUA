package com.opcproxy.ui;

import com.opcproxy.persistence.entity.UserAccount;
import com.opcproxy.persistence.repository.UserAccountRepository;
import com.opcproxy.ui.views.*;
import com.vaadin.flow.component.applayout.AppLayout;
import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.sidenav.SideNav;
import com.vaadin.flow.component.sidenav.SideNavItem;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.Layout;
import com.vaadin.flow.router.RouterLayout;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

@Layout
@PermitAll
public class MainLayout extends AppLayout implements RouterLayout, BeforeEnterObserver {
    private final UserAccountRepository userRepository;

    public MainLayout(UserAccountRepository userRepository) {
        this.userRepository = userRepository;
        createHeader();
        createDrawer();
    }
    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.isAuthenticated() && !"anonymousUser".equals(auth.getName())) {
            UserAccount user = userRepository.findByUsername(auth.getName()).orElse(null);
            if (user != null && Boolean.TRUE.equals(user.getForcePasswordChange())) {
                // Если требуется смена пароля и мы не на экране смены пароля -> редирект
                if (!event.getLocation().getPath().equals("change-password")) {
                    event.forwardTo("change-password");
                }
            }
        }
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
        // Внутри метода createDrawer() класса MainLayout.java
        nav.addItem(new SideNavItem("Tags", TagsView.class));
        nav.addItem(new SideNavItem("Browse", BrowseView.class)); //
        nav.addItem(new SideNavItem("Servers & Tags", MasterDetailView.class));
        nav.addItem(new SideNavItem("Import/Export", ImportExportView.class));
        nav.addItem(new SideNavItem("Calculations", CalcView.class)); //
        nav.addItem(new SideNavItem("Settings", SettingsView.class)); //
        nav.addItem(new SideNavItem("Users", UsersView.class)); //
        drawerLayout.add(nav);
        addToDrawer(drawerLayout);
    }
}