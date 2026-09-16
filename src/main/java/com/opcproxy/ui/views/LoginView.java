package com.opcproxy.ui.views;

import com.vaadin.flow.component.html.H1;
import com.vaadin.flow.component.login.LoginForm;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.router.BeforeEnterEvent;
import com.vaadin.flow.router.BeforeEnterObserver;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.server.auth.AnonymousAllowed;
import com.vaadin.flow.theme.lumo.LumoUtility;

@PageTitle("Login")
@Route(value = "login")
@AnonymousAllowed // Обязательно! Иначе Vaadin заблокирует саму страницу логина
public class LoginView extends VerticalLayout implements BeforeEnterObserver {

    private final LoginForm loginForm = new LoginForm();

    public LoginView() {
        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setAlignItems(Alignment.CENTER);
        setSizeFull();
        setJustifyContentMode(JustifyContentMode.CENTER);

        H1 title = new H1("OPC DA-UA Proxy");
        title.addClassNames(LumoUtility.FontSize.XLARGE, LumoUtility.Margin.Bottom.MEDIUM);

        // Отправляем форму на стандартный endpoint Spring Security
        loginForm.setAction("login");

        add(title, loginForm);
    }

    @Override
    public void beforeEnter(BeforeEnterEvent event) {
        // Если в URL есть ?error, показываем сообщение об ошибке в форме
        if (event.getLocation()
                .getQueryParameters()
                .getParameters()
                .containsKey("error")) {
            loginForm.setError(true);
        }
    }
}