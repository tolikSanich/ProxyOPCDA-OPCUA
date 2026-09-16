package com.opcproxy.ui.views;

import com.opcproxy.opcda.ConnectionState;
import com.opcproxy.opcda.OpcDaClient;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.ui.services.ConnectionService;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.checkbox.Checkbox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.splitlayout.SplitLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.data.provider.ListDataProvider;
import com.vaadin.flow.data.value.ValueChangeMode;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.shared.Registration;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Master-Detail: слева — серверы (мастер), справа — теги выделенного сервера.
 * Без выделения — все теги. Живые значения, фильтры, цветовая индикация.
 */
@Slf4j
@PageTitle("Servers & Tags")
@Route(value = "masterdetail", layout = com.opcproxy.ui.MainLayout.class)
@PermitAll
public class MasterDetailView extends VerticalLayout {

    private static final DateTimeFormatter TS_FMT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss");

    private final ConnectionService connectionService;
    private final TagService tagService;
    private final TagRegistry tagRegistry;
    private final OpcDaConnectionRepository connectionRepository;
    private final ConnectionsDialog connectionsDialog;
    private final TagDialog tagDialog;

    private final Grid<OpcDaConnection> masterGrid = new Grid<>();
    private final Grid<Tag> detailGrid = new Grid<>();

    private final TextField serverFilter = new TextField("Server filter");
    private final TextField tagFilter = new TextField("Tag filter");
    private final Checkbox onlyActiveServers = new Checkbox("Active servers only");
    private final Checkbox onlyGoodTags = new Checkbox("Good tags only");

    private OpcDaConnection selectedServer;      // null = показать все теги
    private ListDataProvider<Tag> detailProvider;
    private ListDataProvider<OpcDaConnection> masterProvider;

    private final Map<Long, Span> tagValueSpans = new ConcurrentHashMap<>();
    private Registration pollRegistration;

    public MasterDetailView(ConnectionService connectionService,
                            TagService tagService,
                            TagRegistry tagRegistry,
                            OpcDaConnectionRepository connectionRepository) {
        this.connectionService = connectionService;
        this.tagService = tagService;
        this.tagRegistry = tagRegistry;
        this.connectionRepository = connectionRepository;
        this.connectionsDialog = new ConnectionsDialog(connectionService, this::refreshMaster);
        this.tagDialog = new TagDialog(tagService, connectionRepository, this::refreshDetail);

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setSizeFull();

        add(createHeader(), createFilters(), createSplit());
        refreshMaster();
    }

    // ---------------- Header ----------------

    private HorizontalLayout createHeader() {
        H2 title = new H2("Servers & Tags");
        Button addServer = new Button("Add Server", new Icon(VaadinIcon.PLUS_CIRCLE));
        addServer.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addServer.addClickListener(e -> connectionsDialog.edit(null));

        Button addTag = new Button("Add Tag", new Icon(VaadinIcon.PLUS));
        addTag.addClickListener(e -> tagDialog.edit(null));

        HorizontalLayout h = new HorizontalLayout(title, addServer, addTag);
        h.setWidthFull();
        h.setAlignItems(FlexComponent.Alignment.CENTER);
        h.expand(title);
        return h;
    }

    // ---------------- Filters ----------------

    private HorizontalLayout createFilters() {
        serverFilter.setPlaceholder("Name / host / ProgID / CLSID…");
        serverFilter.setClearButtonVisible(true);
        serverFilter.setValueChangeMode(ValueChangeMode.LAZY);
        serverFilter.addValueChangeListener(e -> masterGrid.getDataProvider().refreshAll());

        tagFilter.setPlaceholder("Tag name / ItemID…");
        tagFilter.setClearButtonVisible(true);
        tagFilter.setValueChangeMode(ValueChangeMode.LAZY);
        tagFilter.addValueChangeListener(e -> detailGrid.getDataProvider().refreshAll());

        onlyActiveServers.addValueChangeListener(e -> masterGrid.getDataProvider().refreshAll());
        onlyGoodTags.addValueChangeListener(e -> detailGrid.getDataProvider().refreshAll());

        HorizontalLayout h = new HorizontalLayout(serverFilter, tagFilter,
                onlyActiveServers, onlyGoodTags);
        h.setWidthFull();
        h.setAlignItems(FlexComponent.Alignment.END);
        return h;
    }

    // ---------------- Master / Detail ----------------

    private SplitLayout createSplit() {
        buildMasterGrid();
        buildDetailGrid();

        VerticalLayout masterPanel = new VerticalLayout(new H2("Servers"), masterGrid);
        masterPanel.setSizeFull();
        masterPanel.setPadding(false);

        VerticalLayout detailPanel = new VerticalLayout(new H2("Tags"), detailGrid);
        detailPanel.setSizeFull();
        detailPanel.setPadding(false);

        SplitLayout split = new SplitLayout(masterPanel, detailPanel);
        split.setSizeFull();
        split.setSplitterPosition(45);
        return split;
    }

    private void buildMasterGrid() {
        masterGrid.setSizeFull();
        masterGrid.setSelectionMode(Grid.SelectionMode.SINGLE);

        masterGrid.addColumn(OpcDaConnection::getName)
                .setHeader("Name").setFlexGrow(2).setSortable(true);
        masterGrid.addColumn(OpcDaConnection::getHost)
                .setHeader("Host").setFlexGrow(1);
        masterGrid.addComponentColumn(this::statusBadge)
                .setHeader("Status").setFlexGrow(1);
        masterGrid.addComponentColumn(this::tagCountBadge)
                .setHeader("Tags Good/Total").setFlexGrow(1);
        masterGrid.addComponentColumn(this::serverActions)
                .setHeader("Actions").setFlexGrow(0).setWidth("170px");

        // Класс строки — вынесен в отдельный метод с явным типом
        masterGrid.setPartNameGenerator(this::masterRowClass);


        // Выделение сервера → фильтруем детали
        masterGrid.addSelectionListener(e ->
                e.getFirstSelectedItem().ifPresentOrElse(
                        this::selectServer,
                        this::clearServerSelection));
    }
    /**
     * Класс строки мастера: красный — сервер недоступен (не подключён
     * или выключен), зелёный — подключён.
     * Вызывается Grid'ом при каждом refreshAll(), поэтому на poll-е
     * строка перекрашивается автоматически.
     */
    private String masterRowClass(OpcDaConnection conn) {
        boolean enabled = Boolean.TRUE.equals(conn.getEnabled());
        ConnectionState state = connectionService.getStatus(conn.getId());
        boolean connected = enabled && state == ConnectionState.CONNECTED;
        return connected ? "row-ok" : "row-error";
    }
    /**
     * Part-имя строки detail-таблицы: красная — Bad, жёлтая — Uncertain.
     * null = без подсветки.
     */
    private String detailRowClass(Tag tag) {
        String q = qualityOf(tag);
        if (q.startsWith("Bad")) {
            return "row-error";
        }
        if (q.startsWith("Uncertain")) {
            return "row-warning";
        }
        return null;
    }
    private void buildDetailGrid() {
        detailGrid.setSizeFull();

        detailGrid.addColumn(Tag::getName)
                .setHeader("Tag").setFlexGrow(2).setSortable(true);
        detailGrid.addColumn(this::connectionNameOf)
                .setHeader("Server").setFlexGrow(1);
        detailGrid.addColumn(Tag::getSourceItemId)
                .setHeader("ItemID").setFlexGrow(2);
        detailGrid.addComponentColumn(this::tagValueSpan)
                .setHeader("Value").setFlexGrow(1);
        detailGrid.addComponentColumn(this::qualityBadge)
                .setHeader("Quality").setFlexGrow(1);
        detailGrid.addComponentColumn(this::tagActions)
                .setHeader("Actions").setFlexGrow(0).setWidth("110px");

        detailGrid.setPartNameGenerator(this::detailRowClass);

        detailProvider = new ListDataProvider<>(List.of());
        detailGrid.setDataProvider(detailProvider);
    }

    // ---------------- Selection ----------------

    private void selectServer(OpcDaConnection conn) {
        selectedServer = conn;
        refreshDetail();
    }

    private void clearServerSelection() {
        selectedServer = null;
        refreshDetail();
    }

    private List<Tag> currentTags() {
        List<Tag> tags = tagService.findAll();
        if (selectedServer != null) {
            tags = tags.stream()
                    .filter(t -> t.getConnection() != null
                            && selectedServer.getId().equals(t.getConnection().getId()))
                    .collect(Collectors.toList());
        }
        return tags;
    }

    private String connectionNameOf(Tag tag) {
        return tag.getConnection() != null ? tag.getConnection().getName() : "—";
    }

    // ---------------- Refresh ----------------

    private void refreshMaster() {
        List<OpcDaConnection> all = connectionService.findAll();

        boolean onlyActive = onlyActiveServers.getValue();
        String f = serverFilter.getValue() == null ? "" : serverFilter.getValue().toLowerCase();

        List<OpcDaConnection> filtered = all.stream()
                .filter(c -> !onlyActive || Boolean.TRUE.equals(c.getEnabled()))
                .filter(c -> f.isEmpty()
                        || contains(c.getName(), f)
                        || contains(c.getHost(), f)
                        || contains(c.getProgIdOrClsid(), f))
                .collect(Collectors.toList());

        masterProvider = new ListDataProvider<>(filtered);
        masterGrid.setDataProvider(masterProvider);

        // если выделенный сервер пропал из фильтра — сброс
        if (selectedServer != null && filtered.stream()
                .noneMatch(c -> c.getId().equals(selectedServer.getId()))) {
            masterGrid.deselectAll(); // вызовет clearServerSelection
        }
    }

    private void refreshDetail() {
        tagValueSpans.clear();

        String f = tagFilter.getValue() == null ? "" : tagFilter.getValue().toLowerCase();
        boolean onlyGood = onlyGoodTags.getValue();

        List<Tag> filtered = currentTags().stream()
                .filter(t -> f.isEmpty()
                        || contains(t.getName(), f)
                        || contains(t.getSourceItemId(), f))
                .filter(t -> !onlyGood || qualityOf(t).startsWith("Good"))
                .collect(Collectors.toList());

        detailProvider = new ListDataProvider<>(filtered);
        detailGrid.setDataProvider(detailProvider);
    }

    private static boolean contains(String s, String lower) {
        return s != null && s.toLowerCase().contains(lower);
    }

    // ---------------- Badges / colors ----------------

    private String qualityOf(Tag tag) {
        return tagRegistry.getTagValue(tag.getId())
                .map(TagRegistry.TagValue::getQuality).orElse("Bad_NotRegistered");
    }

    private Span statusBadge(OpcDaConnection conn) {
        boolean enabled = Boolean.TRUE.equals(conn.getEnabled());
        ConnectionState st = connectionService.getStatus(conn.getId());
        String text = !enabled ? "DISABLED"
                : st == ConnectionState.CONNECTED ? "CONNECTED" : st.name();
        Span s = new Span(text);
        s.addClassNames(LumoUtility.Padding.Horizontal.SMALL, LumoUtility.FontSize.XSMALL,
                LumoUtility.FontWeight.BOLD, LumoUtility.BorderRadius.MEDIUM);
        String color = !enabled || st != ConnectionState.CONNECTED
                ? "var(--lumo-error-color)" : "var(--lumo-success-color)";
        s.getStyle().set("color", color);
        return s;
    }

    private Span tagCountBadge(OpcDaConnection conn) {
        List<Tag> tags = tagService.findAll().stream()
                .filter(t -> t.getConnection() != null
                        && conn.getId().equals(t.getConnection().getId()))
                .toList();
        long good = tags.stream().filter(t -> qualityOf(t).startsWith("Good")).count();
        Span s = new Span(good + "/" + tags.size());
        s.addClassNames(LumoUtility.FontSize.XSMALL);
        s.getStyle().set("color", good == tags.size() && !tags.isEmpty()
                ? "var(--lumo-success-color)" : "var(--lumo-warning-color)");
        return s;
    }

    private Span tagValueSpan(Tag tag) {
        Span span = new Span("—");
        span.addClassNames(LumoUtility.FontSize.SMALL);
        tagValueSpans.put(tag.getId(), span);
        applyValue(tag.getId());
        return span;
    }

    private void applyValue(Long tagId) {
        Span span = tagValueSpans.get(tagId);
        if (span == null) return;
        var tv = tagRegistry.getTagValue(tagId).orElse(null);
        Object v = tv != null ? tv.getValue() : null;
        String q = tv != null ? tv.getQuality() : "Bad";
        span.setText(v != null ? String.valueOf(v) : "—");
        span.getStyle().set("color", colorForQuality(q));
        if (tv != null && tv.getTimestamp() != null) {
            span.getElement().setProperty("title", "Updated: "
                    + TS_FMT.format(tv.getTimestamp().atZone(ZoneId.systemDefault())));
        }
    }

    private String colorForQuality(String q) {
        if (q.startsWith("Good")) return "var(--lumo-success-color)";
        if (q.startsWith("Uncertain")) return "var(--lumo-warning-color)";
        return "var(--lumo-error-color)";
    }

    private Span qualityBadge(Tag tag) {
        String q = qualityOf(tag);
        Span s = new Span(q);
        s.addClassNames(LumoUtility.Padding.Horizontal.SMALL, LumoUtility.FontSize.XSMALL,
                LumoUtility.FontWeight.BOLD, LumoUtility.BorderRadius.MEDIUM);
        s.getStyle().set("color", colorForQuality(q));
        return s;
    }

    // ---------------- Actions ----------------

    private HorizontalLayout serverActions(OpcDaConnection conn) {
        boolean enabled = Boolean.TRUE.equals(conn.getEnabled());

        Button toggle = new Button(enabled ? "Disable" : "Enable");
        toggle.addThemeVariants(ButtonVariant.LUMO_SMALL,
                enabled ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS);
        toggle.addClickListener(e -> {
            connectionService.setEnabled(conn.getId(), !enabled);
            refreshMaster();
        });

        Button edit = new Button(new Icon(VaadinIcon.EDIT));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        edit.addClickListener(e -> connectionsDialog.edit(
                connectionService.findById(conn.getId()).orElse(null)));

        Button reconnect = new Button(new Icon(VaadinIcon.REFRESH));
        reconnect.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        reconnect.addClickListener(e -> {
            connectionService.reconnect(conn.getId());
            refreshMaster();
        });

        HorizontalLayout h = new HorizontalLayout(toggle, edit, reconnect);
        h.setSpacing(false);
        h.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        return h;
    }

    private HorizontalLayout tagActions(Tag tag) {
        boolean enabled = Boolean.TRUE.equals(tag.getEnabled());

        Button toggle = new Button(enabled ? "Disable" : "Enable");
        toggle.addThemeVariants(ButtonVariant.LUMO_SMALL,
                enabled ? ButtonVariant.LUMO_ERROR : ButtonVariant.LUMO_SUCCESS);
        toggle.addClickListener(e -> {
            tagService.setEnabled(tag.getId(), !enabled);
            refreshDetail();
            refreshMaster(); // счётчик тегов тоже меняется
        });

        Button edit = new Button(new Icon(VaadinIcon.EDIT));
        edit.addThemeVariants(ButtonVariant.LUMO_SMALL, ButtonVariant.LUMO_TERTIARY);
        edit.addClickListener(e -> tagService.findById(tag.getId())
                .ifPresentOrElse(tagDialog::edit,
                        () -> Notification.show("Tag not found", 2000, Notification.Position.MIDDLE)));

        HorizontalLayout h = new HorizontalLayout(toggle, edit);
        h.setSpacing(false);
        h.setJustifyContentMode(FlexComponent.JustifyContentMode.CENTER);
        return h;
    }

    // ---------------- Live updates ----------------

    @Override
    protected void onAttach(com.vaadin.flow.component.AttachEvent event) {
        super.onAttach(event);
        UI ui = event.getUI();
        ui.setPollInterval(1000);
        pollRegistration = ui.addPollListener(e -> {
            tagValueSpans.keySet().forEach(this::applyValue);
            masterGrid.getDataProvider().refreshAll(); // пересчитывает masterRowClass
        });
    }

    @Override
    protected void onDetach(com.vaadin.flow.component.DetachEvent event) {
        if (pollRegistration != null) {
            pollRegistration.remove();
            pollRegistration = null;
        }
        event.getUI().setPollInterval(-1);
        super.onDetach(event);
    }
}