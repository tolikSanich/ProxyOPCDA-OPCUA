package com.opcproxy.ui.views;

import com.opcproxy.calc.CalcDependencyValidator;
import com.opcproxy.opcda.OpcDaConnectionManager;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.enums.DataType;
import com.opcproxy.persistence.enums.SourceType;
import com.opcproxy.persistence.repository.IntervalProfileRepository;
import com.opcproxy.persistence.repository.OpcDaConnectionRepository;
import com.opcproxy.rest.dto.RestDtos.BrowseNodeDto;
import com.opcproxy.ui.MainLayout;
import com.opcproxy.ui.services.ConnectionService;
import com.opcproxy.ui.services.TagService;
import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.combobox.ComboBox;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.H2;
import com.vaadin.flow.component.html.Span;
import com.vaadin.flow.component.icon.Icon;
import com.vaadin.flow.component.icon.VaadinIcon;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.FlexComponent;
import com.vaadin.flow.component.orderedlayout.HorizontalLayout;
import com.vaadin.flow.component.orderedlayout.VerticalLayout;
import com.vaadin.flow.component.textfield.TextField;
import com.vaadin.flow.component.treegrid.TreeGrid;
import com.vaadin.flow.data.provider.hierarchy.TreeData;
import com.vaadin.flow.data.provider.hierarchy.TreeDataProvider;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.theme.lumo.LumoUtility;
import jakarta.annotation.security.PermitAll;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Экран браузинга пространства имён OPC DA-сервера (ТЗ §5.8.3.4).
 */
@Slf4j
@PageTitle("OPC DA Browse")
@Route(value = "browse", layout = MainLayout.class)
@PermitAll
public class BrowseView extends VerticalLayout {

    private final ConnectionService connectionService;
    private final OpcDaConnectionManager connectionManager;
    private final TagService tagService;
    private final TagDialog tagDialog;

    private final ComboBox<OpcDaConnection> connectionBox = new ComboBox<>("OPC DA Connection");
    private final TextField searchField = new TextField("Search");
    private final Button refreshButton = new Button("Refresh", new Icon(VaadinIcon.REFRESH));
    private final Button addTagButton = new Button("Add as Tag", new Icon(VaadinIcon.PLUS));

    private final TreeGrid<BrowseNodeDto> treeGrid = new TreeGrid<>();
    private TreeData<BrowseNodeDto> treeData = new TreeData<>();

    private OpcDaConnection selectedConnection;
    private List<BrowseNodeDto> currentBrowseResult = new ArrayList<>();

    public BrowseView(ConnectionService connectionService,
                      OpcDaConnectionManager connectionManager,
                      TagService tagService,
                      OpcDaConnectionRepository connectionRepository,
                      IntervalProfileRepository profileRepository,
                      CalcDependencyValidator calcValidator) {
        this.connectionService = connectionService;
        this.connectionManager = connectionManager;
        this.tagService = tagService;

        // Инициализируем TagDialog вручную (не как Spring @Bean), чтобы избежать проблем с областью видимости (scope)
        this.tagDialog = new TagDialog(
                tagService,
                connectionRepository,
                calcValidator,
                () -> Notification.show("Tag added successfully", 2000, Notification.Position.BOTTOM_END),
                profileRepository
        );

        addClassNames(LumoUtility.Padding.MEDIUM, LumoUtility.Gap.MEDIUM);
        setSizeFull();

        createHeader();
        createControls();
        createTreeGrid();

        refreshConnections();
    }

    private void createHeader() {
        H2 title = new H2("OPC DA Browser");
        title.addClassNames(LumoUtility.Margin.Bottom.NONE);
        add(title);
    }

    private void createControls() {
        connectionBox.setItems(connectionService.findAll());
        connectionBox.setItemLabelGenerator(OpcDaConnection::getName);
        connectionBox.setPlaceholder("Select OPC DA connection...");
        connectionBox.setWidth("300px");
        connectionBox.addValueChangeListener(e -> {
            selectedConnection = e.getValue();
            if (selectedConnection != null) {
                loadBrowseData();
            } else {
                clearTree();
            }
        });

        searchField.setPlaceholder("Search by name or ItemID...");
        searchField.setClearButtonVisible(true);
        searchField.setWidth("300px");
        searchField.addValueChangeListener(e -> applyFilter(e.getValue()));

        refreshButton.addThemeVariants(ButtonVariant.LUMO_TERTIARY);
        refreshButton.addClickListener(e -> refreshBrowse());
        refreshButton.setEnabled(false);

        addTagButton.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        addTagButton.addClickListener(e -> addSelectedAsTag());
        addTagButton.setEnabled(false);

        HorizontalLayout controls = new HorizontalLayout(
                connectionBox, searchField, refreshButton, addTagButton
        );
        controls.setWidthFull();
        controls.setAlignItems(FlexComponent.Alignment.END);
        controls.expand(searchField);

        add(controls);
    }

    private void createTreeGrid() {
        treeGrid.setWidthFull();
        treeGrid.setHeight("600px");

        treeGrid.addColumn(BrowseNodeDto::name)
                .setHeader("Name")
                .setFlexGrow(2)
                .setSortable(true);

        treeGrid.addColumn(node -> node.itemId() != null ? node.itemId() : "—")
                .setHeader("ItemID")
                .setFlexGrow(2)
                .setSortable(true);

        treeGrid.addComponentColumn(node -> {
                    Span badge = new Span(node.isBranch() ? "Branch" : "Leaf");
                    badge.addClassNames(
                            LumoUtility.Padding.Horizontal.SMALL,
                            LumoUtility.Padding.Vertical.XSMALL,
                            LumoUtility.BorderRadius.MEDIUM,
                            LumoUtility.FontSize.XSMALL,
                            LumoUtility.FontWeight.BOLD
                    );
                    if (node.isBranch()) {
                        badge.getStyle().set("background-color", "var(--lumo-primary-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-primary-color)");
                    } else {
                        badge.getStyle().set("background-color", "var(--lumo-success-contrast-color)");
                        badge.getStyle().set("color", "var(--lumo-success-color)");
                    }
                    return badge;
                })
                .setHeader("Type")
                .setFlexGrow(1);

        treeGrid.setSelectionMode(Grid.SelectionMode.SINGLE);
        treeGrid.addSelectionListener(e -> {
            BrowseNodeDto selected = e.getFirstSelectedItem().orElse(null);
            addTagButton.setEnabled(selected != null && !selected.isBranch());
        });

        treeGrid.setDataProvider(new TreeDataProvider<>(treeData));
        add(treeGrid);
    }

    private void refreshConnections() {
        connectionBox.setItems(connectionService.findAll());
    }

    private void loadBrowseData() {
        if (selectedConnection == null) return;

        try {
            Notification.show("Loading browse data...", 2000, Notification.Position.MIDDLE);
            currentBrowseResult = connectionManager.browse(selectedConnection.getId());
            buildTreeData(currentBrowseResult);
            refreshButton.setEnabled(true);
            Notification.show("Loaded " + countLeaves(currentBrowseResult) + " tags",
                    2000, Notification.Position.BOTTOM_END);
        } catch (Exception e) {
            log.error("Failed to browse connection {}: {}", selectedConnection.getName(), e.getMessage());
            Notification.show("Error: " + e.getMessage(), 5000, Notification.Position.MIDDLE);
            clearTree();
        }
    }

    private void refreshBrowse() {
        if (selectedConnection == null) return;
        connectionManager.invalidateBrowseCache(selectedConnection.getId());
        loadBrowseData();
    }

    private void buildTreeData(List<BrowseNodeDto> roots) {
        treeData = new TreeData<>();
        for (BrowseNodeDto root : roots) {
            addRecursive(treeData, root, null);
        }
        treeGrid.setDataProvider(new TreeDataProvider<>(treeData));
    }

    private void addRecursive(TreeData<BrowseNodeDto> treeData, BrowseNodeDto node, BrowseNodeDto parent) {
        treeData.addItem(parent, node);
        if (node.children() != null && !node.children().isEmpty()) {
            for (BrowseNodeDto child : node.children()) {
                addRecursive(treeData, child, node);
            }
        }
    }

    private void clearTree() {
        treeData = new TreeData<>();
        treeGrid.setDataProvider(new TreeDataProvider<>(treeData));
        refreshButton.setEnabled(false);
        addTagButton.setEnabled(false);
        currentBrowseResult = new ArrayList<>();
    }

    private void applyFilter(String filterText) {
        if (filterText == null || filterText.isBlank()) {
            buildTreeData(currentBrowseResult);
            return;
        }

        String lowerFilter = filterText.toLowerCase();
        Set<BrowseNodeDto> matchingNodes = new HashSet<>();
        Set<BrowseNodeDto> parentNodes = new HashSet<>();

        for (BrowseNodeDto root : currentBrowseResult) {
            collectMatching(root, lowerFilter, matchingNodes, parentNodes, null);
        }

        TreeData<BrowseNodeDto> filteredData = new TreeData<>();
        for (BrowseNodeDto root : currentBrowseResult) {
            if (matchingNodes.contains(root) || parentNodes.contains(root)) {
                addFilteredRecursive(filteredData, root, null, lowerFilter, matchingNodes, parentNodes);
            }
        }

        treeGrid.setDataProvider(new TreeDataProvider<>(filteredData));
    }

    private void collectMatching(BrowseNodeDto node, String filter,
                                 Set<BrowseNodeDto> matching, Set<BrowseNodeDto> parents,
                                 BrowseNodeDto parent) {
        boolean matches = (node.name() != null && node.name().toLowerCase().contains(filter)) ||
                (node.itemId() != null && node.itemId().toLowerCase().contains(filter));

        if (matches) {
            matching.add(node);
            BrowseNodeDto current = parent;
            while (current != null) {
                parents.add(current);
                current = findParent(current, currentBrowseResult, null);
            }
        }

        if (node.children() != null) {
            for (BrowseNodeDto child : node.children()) {
                collectMatching(child, filter, matching, parents, node);
            }
        }
    }

    private BrowseNodeDto findParent(BrowseNodeDto target, List<BrowseNodeDto> nodes, BrowseNodeDto parent) {
        for (BrowseNodeDto node : nodes) {
            if (node.equals(target)) {
                return parent;
            }
            if (node.children() != null) {
                BrowseNodeDto found = findParent(target, node.children(), node);
                if (found != null) return found;
            }
        }
        return null;
    }

    private void addFilteredRecursive(TreeData<BrowseNodeDto> treeData, BrowseNodeDto node,
                                      BrowseNodeDto parent, String filter,
                                      Set<BrowseNodeDto> matching, Set<BrowseNodeDto> parents) {
        treeData.addItem(parent, node);
        if (node.children() != null) {
            for (BrowseNodeDto child : node.children()) {
                if (matching.contains(child) || parents.contains(child)) {
                    addFilteredRecursive(treeData, child, node, filter, matching, parents);
                }
            }
        }
    }

    private void addSelectedAsTag() {
        BrowseNodeDto selected = treeGrid.getSelectedItems().stream().findFirst().orElse(null);
        if (selected == null || selected.isBranch() || selectedConnection == null) {
            Notification.show("Select a leaf node first", 2000, Notification.Position.MIDDLE);
            return;
        }

        Tag newTag = new Tag();
        newTag.setName(generateUniqueTagName(selected.name()));
        newTag.setSourceType(SourceType.DA);
        newTag.setConnection(selectedConnection);
        newTag.setSourceItemId(selected.itemId());
        newTag.setDataType(DataType.STRING);
        newTag.setEnabled(true);

        tagDialog.edit(newTag);
    }

    private String generateUniqueTagName(String baseName) {
        String name = baseName;
        int counter = 1;
        while (tagService.existsByName(name)) {
            name = baseName + "_" + counter;
            counter++;
        }
        return name;
    }

    private int countLeaves(List<BrowseNodeDto> nodes) {
        int count = 0;
        for (BrowseNodeDto node : nodes) {
            if (!node.isBranch()) {
                count++;
            }
            if (node.children() != null) {
                count += countLeaves(node.children());
            }
        }
        return count;
    }
}