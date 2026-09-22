package com.opcproxy.ui.views;

import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.SettingsService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.H3;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.tabs.Tab;
import com.vaadin.flow.component.tabs.Tabs;
import com.vaadin.flow.component.textfield.IntegerField;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.HashMap;
import java.util.Map;

/**
 * Экран настроек системы (ТЗ §5.8.3.7).
 * Вкладки: OPC UA, MQTT, OPC DA, Безопасность, Журналы.
 */
@Slf4j
@PageTitle("Settings")
@Route(value = "settings", layout = MainLayout.class)
@PermitAll
public class SettingsView extends VerticalLayout {

    private final SettingsService settingsService;

    private final Tabs tabs = new Tabs();
    private final VerticalLayout content = new VerticalLayout();

    // OPC UA
    private final IntegerField opcUaPortField = new IntegerField("Port");
    private final TextField opcUaHostnameField = new TextField("Hostname");
    private final TextField opcUaPoliciesField = new TextField("Enabled Policies");
    private final Checkbox opcUaTrustAllCheckbox = new Checkbox("Trust All Clients");
    private final IntegerField opcUaSamplingField = new IntegerField("Default Sampling Interval (ms)");

    // MQTT
    private final Checkbox mqttEnabledCheckbox = new Checkbox("Enable MQTT");
    private final TextField mqttBrokerField = new TextField("Broker URL");
    private final TextField mqttBaseTopicField = new TextField("Base Topic");
    private final IntegerField mqttQosField = new IntegerField("QoS");
    private final Checkbox mqttRetainCheckbox = new Checkbox("Retain");
    private final IntegerField mqttRefreshField = new IntegerField("Config Refresh (ms)");

    // OPC DA
    private final IntegerField opcDaRefreshField = new IntegerField("Default Refresh Period (ms)");
    private final IntegerField opcDaReconnectField = new IntegerField("Reconnect Interval (ms)");
    private final IntegerField opcDaMaxConnField = new IntegerField("Max Connections");
    private final IntegerField opcDaTimeoutField = new IntegerField("Connection Timeout (ms)");

    public SettingsView(SettingsService settingsService) {
        this.settingsService = settingsService;
        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setSizeFull();

        createHeader();
        createTabs();
        loadSettings();
    }

    private void createHeader() {
        H2 title = new H2("System Settings");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);
        add(title);
    }

    private void createTabs() {
        Tab opcUaTab = new Tab("OPC UA");
        Tab mqttTab = new Tab("MQTT");
        Tab opcDaTab = new Tab("OPC DA");
        Tab securityTab = new Tab("Security");
        Tab logsTab = new Tab("Logs");

        tabs.add(opcUaTab, mqttTab, opcDaTab, securityTab, logsTab);
        tabs.addSelectedChangeListener(e -> {
            Tab selected = tabs.getSelectedTab();
            if (selected == opcUaTab) showOpcUaSettings();
            else if (selected == mqttTab) showMqttSettings();
            else if (selected == opcDaTab) showOpcDaSettings();
            else if (selected == securityTab) showSecuritySettings();
            else if (selected == logsTab) showLogsSettings();
        });

        add(tabs);
        content.setSizeFull();
        add(content);

        // По умолчанию показываем OPC UA
        showOpcUaSettings();
    }

    private void loadSettings() {
        // OPC UA
        Map<String, String> opcUa = settingsService.getOpcUaSettings();
        opcUaPortField.setValue(Integer.parseInt(opcUa.getOrDefault("port", "4840")));
        opcUaHostnameField.setValue(opcUa.getOrDefault("hostname", "localhost"));
        opcUaPoliciesField.setValue(opcUa.getOrDefault("enabledPolicies", "None,Basic256Sha256"));
        opcUaTrustAllCheckbox.setValue(Boolean.parseBoolean(opcUa.getOrDefault("trustAllClients", "true")));
        opcUaSamplingField.setValue(Integer.parseInt(opcUa.getOrDefault("defaultSamplingIntervalMs", "500")));

        // MQTT
        Map<String, String> mqtt = settingsService.getMqttSettings();
        mqttEnabledCheckbox.setValue(Boolean.parseBoolean(mqtt.getOrDefault("enabled", "false")));
        mqttBrokerField.setValue(mqtt.getOrDefault("broker", "tcp://localhost:1883"));
        mqttBaseTopicField.setValue(mqtt.getOrDefault("baseTopic", "gateway/default"));
        mqttQosField.setValue(Integer.parseInt(mqtt.getOrDefault("qos", "1")));
        mqttRetainCheckbox.setValue(Boolean.parseBoolean(mqtt.getOrDefault("retain", "false")));
        mqttRefreshField.setValue(Integer.parseInt(mqtt.getOrDefault("configRefreshMs", "5000")));

        // OPC DA
        Map<String, String> opcDa = settingsService.getOpcDaSettings();
        opcDaRefreshField.setValue(Integer.parseInt(opcDa.getOrDefault("defaultRefreshPeriodMs", "1000")));
        opcDaReconnectField.setValue(Integer.parseInt(opcDa.getOrDefault("reconnectIntervalMs", "5000")));
        opcDaMaxConnField.setValue(Integer.parseInt(opcDa.getOrDefault("maxConnections", "10")));
        opcDaTimeoutField.setValue(Integer.parseInt(opcDa.getOrDefault("connectionTimeoutMs", "10000")));
    }

    private void showOpcUaSettings() {
        content.removeAll();

        H3 title = new H3("OPC UA Server Settings");

        opcUaPortField.setMin(1024);
        opcUaPortField.setMax(65535);
        opcUaSamplingField.setMin(100);
        opcUaSamplingField.setStep(100);

        Button saveBtn = new Button("Save OPC UA Settings", new Icon(VaadinIcon.CHECK));
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> saveOpcUaSettings());

        VerticalLayout layout = new VerticalLayout(title,
                opcUaPortField, opcUaHostnameField, opcUaPoliciesField,
                opcUaTrustAllCheckbox, opcUaSamplingField, saveBtn);
        layout.setPadding(false);
        content.add(layout);
    }

    private void showMqttSettings() {
        content.removeAll();

        H3 title = new H3("MQTT Publisher Settings");

        mqttQosField.setMin(0);
        mqttQosField.setMax(2);
        mqttRefreshField.setMin(1000);
        mqttRefreshField.setStep(1000);

        Button saveBtn = new Button("Save MQTT Settings", new Icon(VaadinIcon.CHECK));
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> saveMqttSettings());

        VerticalLayout layout = new VerticalLayout(title,
                mqttEnabledCheckbox, mqttBrokerField, mqttBaseTopicField,
                mqttQosField, mqttRetainCheckbox, mqttRefreshField, saveBtn);
        layout.setPadding(false);
        content.add(layout);
    }

    private void showOpcDaSettings() {
        content.removeAll();

        H3 title = new H3("OPC DA Connection Settings");

        opcDaRefreshField.setMin(100);
        opcDaRefreshField.setStep(100);
        opcDaReconnectField.setMin(1000);
        opcDaReconnectField.setStep(1000);
        opcDaMaxConnField.setMin(1);
        opcDaMaxConnField.setMax(100);
        opcDaTimeoutField.setMin(1000);
        opcDaTimeoutField.setStep(1000);

        Button saveBtn = new Button("Save OPC DA Settings", new Icon(VaadinIcon.CHECK));
        saveBtn.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        saveBtn.addClickListener(e -> saveOpcDaSettings());

        VerticalLayout layout = new VerticalLayout(title,
                opcDaRefreshField, opcDaReconnectField, opcDaMaxConnField,
                opcDaTimeoutField, saveBtn);
        layout.setPadding(false);
        content.add(layout);
    }

    private void showSecuritySettings() {
        content.removeAll();

        H3 title = new H3("Security Settings");
        Span info = new Span("Security settings are managed via application.yml and Jasypt encryption.");
        info.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout layout = new VerticalLayout(title, info);
        layout.setPadding(false);
        content.add(layout);
    }

    private void showLogsSettings() {
        content.removeAll();

        H3 title = new H3("Logging Settings");
        Span info = new Span("Logging levels are configured in application.yml. Changes require application restart.");
        info.addClassNames(LumoUtility.FontSize.SMALL, LumoUtility.TextColor.SECONDARY);

        VerticalLayout layout = new VerticalLayout(title, info);
        layout.setPadding(false);
        content.add(layout);
    }

    private void saveOpcUaSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("port", String.valueOf(opcUaPortField.getValue()));
        settings.put("hostname", opcUaHostnameField.getValue());
        settings.put("enabledPolicies", opcUaPoliciesField.getValue());
        settings.put("trustAllClients", String.valueOf(opcUaTrustAllCheckbox.getValue()));
        settings.put("defaultSamplingIntervalMs", String.valueOf(opcUaSamplingField.getValue()));

        settingsService.saveSettings("opcua", settings);
        Notification.show("OPC UA settings saved. Restart required for some changes.",
                3000, Notification.Position.BOTTOM_END);
    }

    private void saveMqttSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("enabled", String.valueOf(mqttEnabledCheckbox.getValue()));
        settings.put("broker", mqttBrokerField.getValue());
        settings.put("baseTopic", mqttBaseTopicField.getValue());
        settings.put("qos", String.valueOf(mqttQosField.getValue()));
        settings.put("retain", String.valueOf(mqttRetainCheckbox.getValue()));
        settings.put("configRefreshMs", String.valueOf(mqttRefreshField.getValue()));

        settingsService.saveSettings("mqtt", settings);
        Notification.show("MQTT settings saved. Restart required for some changes.",
                3000, Notification.Position.BOTTOM_END);
    }

    private void saveOpcDaSettings() {
        Map<String, String> settings = new HashMap<>();
        settings.put("defaultRefreshPeriodMs", String.valueOf(opcDaRefreshField.getValue()));
        settings.put("reconnectIntervalMs", String.valueOf(opcDaReconnectField.getValue()));
        settings.put("maxConnections", String.valueOf(opcDaMaxConnField.getValue()));
        settings.put("connectionTimeoutMs", String.valueOf(opcDaTimeoutField.getValue()));

        settingsService.saveSettings("opcda", settings);
        Notification.show("OPC DA settings saved. Restart required for some changes.",
                3000, Notification.Position.BOTTOM_END);
    }
}