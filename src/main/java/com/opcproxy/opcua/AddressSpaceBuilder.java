package com.opcproxy.opcua;

import com.opcproxy.config.OpcUaConfig;
import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import com.opcproxy.tags.TagRegistry;
import com.opcproxy.tags.TagValueUpdatedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedNamespaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.OpcUaServer;
import org.eclipse.milo.opcua.sdk.server.SessionListener;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.stack.core.Identifiers;
import org.eclipse.milo.opcua.stack.core.types.builtin.*;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class AddressSpaceBuilder {

    private final TagRegistry tagRegistry;
    private final TagRepository tagRepository;
    private final OpcUaConfig opcUaConfig;   // namespace URI из конфигурации
    private final SamplingIntervalResolver samplingResolver;

    private volatile boolean built = false;
    private CustomNamespace namespace;
    private final Map<Long, NodeId> tagIdToNodeIdMap = new ConcurrentHashMap<>();

    /** OPC UA StatusCode для Uncertain. */
    private static final long STATUS_UNCERTAIN = 0x4000_0000L;
    /** OPC UA StatusCode для Bad (generic). */
    private static final long STATUS_BAD = 0x8000_0000L;

    public void build(OpcUaServer server) {
        if (built) {
            log.warn("Address Space already built, skipping duplicate invocation");
            return;
        }
        built = true;

        namespace = new CustomNamespace(server, opcUaConfig.getNamespaceUri());
        namespace.startup();

        buildAddressSpace();
        server.getSessionManager().addSessionListener(new SessionListener() {
            @Override
            public void onSessionCreated(org.eclipse.milo.opcua.sdk.server.Session session) {
                log.info("UA session created: id={}, app='{}'",
                        session.getSessionId(), session.getSessionName());
            }

            @Override
            public void onSessionClosed(org.eclipse.milo.opcua.sdk.server.Session session) {
                log.info("UA session closed: id={}, app='{}'",
                        session.getSessionId(), session.getSessionName());
            }
        });
    }

    private void buildAddressSpace() {
        log.info("Building OPC UA Address Space...");

        // 1. Корневая папка "Tags" (плоский контур, совместимость по ТЗ §5.5.3)
        UaFolderNode tagsFolder = new UaFolderNode(
                namespace.getNodeContext(),
                new NodeId(namespace.getNamespaceIndex(), "Tags"),
                new QualifiedName(namespace.getNamespaceIndex(), "Tags"),
                LocalizedText.english("Tags")
        );
        namespace.getNodeManager().addNode(tagsFolder);
        tagsFolder.addReference(new Reference(
                tagsFolder.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));

        // 2. Корневая папка "Sources" (иерархия по подключениям)
        UaFolderNode sourcesFolder = new UaFolderNode(
                namespace.getNodeContext(),
                new NodeId(namespace.getNamespaceIndex(), "Sources"),
                new QualifiedName(namespace.getNamespaceIndex(), "Sources"),
                LocalizedText.english("Sources")
        );
        namespace.getNodeManager().addNode(sourcesFolder);
        sourcesFolder.addReference(new Reference(
                sourcesFolder.getNodeId(),
                Identifiers.Organizes,
                Identifiers.ObjectsFolder.expanded(),
                false
        ));

        // Загружаем теги из БД вместе с connection (fetch join — нет Lazy-прокси)
        List<Tag> tags = tagRepository.findAllEnabledWithConnection();
        for (Tag tag : tags) {
            createTagNode(tag, tagsFolder, sourcesFolder);
        }
        log.info("Building OPC UA Address Space...");
        log.info(">>> Custom namespace index = {} (URI: {})",
                namespace.getNamespaceIndex(), opcUaConfig.getNamespaceUri());
        log.info("Address Space built successfully. Total enabled tags: {}", tags.size());
    }

    private void createTagNode(Tag tag, UaFolderNode tagsFolder, UaFolderNode sourcesFolder) {
        String tagName = tag.getName();
        NodeId nodeId = new NodeId(namespace.getNamespaceIndex(), tagName);
        QualifiedName browseName = new QualifiedName(namespace.getNamespaceIndex(), tagName);

        Object initialValue = tagRegistry.getTagValue(tag.getId())
                .map(TagRegistry.TagValue::getValue)
                .orElse(null);

        UaVariableNode variableNode = new UaVariableNode.UaVariableNodeBuilder(namespace.getNodeContext())
                .setNodeId(nodeId)
                .setBrowseName(browseName)
                .setDisplayName(LocalizedText.english(tagName))
                .setDataType(getOpcUaDataType(tag.getDataType().name()))
                .setTypeDefinition(Identifiers.BaseDataVariableType)
                .setAccessLevel(EnumSet.of(AccessLevel.CurrentRead))
                .setUserAccessLevel(EnumSet.of(AccessLevel.CurrentRead))
                .setValue(new DataValue(new Variant(initialValue)))
                .build();
        // ТЗ §5.3: эффективный интервал тега как MinimumSamplingInterval узла.
        // SDK Milo сам ревизует слишком быстрые запросы клиентов (см. milo#517).
        variableNode.setMinimumSamplingInterval(samplingResolver.resolve(tag));
        namespace.getNodeManager().addNode(variableNode);
        tagIdToNodeIdMap.put(tag.getId(), nodeId);
        tagsFolder.addOrganizes(variableNode);

        // Иерархия Sources/<Connection>/<Tag>
        if (tag.getConnection() != null) {
            String connName = tag.getConnection().getName();
            NodeId connFolderId = new NodeId(namespace.getNamespaceIndex(), "Sources/" + connName);

            UaFolderNode connFolder =
                    (UaFolderNode) namespace.getNodeManager().getNode(connFolderId).orElse(null);
            if (connFolder == null) {
                connFolder = new UaFolderNode(
                        namespace.getNodeContext(),
                        connFolderId,
                        new QualifiedName(namespace.getNamespaceIndex(), connName),
                        LocalizedText.english(connName)
                );
                namespace.getNodeManager().addNode(connFolder);
                sourcesFolder.addOrganizes(connFolder);
            }
            connFolder.addOrganizes(variableNode);
        }
    }

    @EventListener
    public void onTagValueUpdated(TagValueUpdatedEvent event) {
        NodeId nodeId = tagIdToNodeIdMap.get(event.getTagId());
        if (nodeId != null && namespace != null) {
            var node = (UaVariableNode) namespace.getNodeManager().getNode(nodeId).orElse(null);
            if (node != null) {
                StatusCode statusCode = statusCodeFor(event.getQuality());
                Instant serverTime = Instant.now();
                DateTime sourceDateTime = event.getSourceTimestamp() != null
                        ? new DateTime(event.getSourceTimestamp())
                        : new DateTime(serverTime);

                DataValue dataValue = new DataValue(
                        new Variant(event.getValue()),
                        statusCode,
                        sourceDateTime,
                        new DateTime(serverTime)
                );
                node.setValue(dataValue);
            }
        }
    }

    /**
     * Маппинг качества OPC DA -> OPC UA StatusCode.
     * Good -> GOOD, Uncertain -> 0x40000000, Bad -> 0x80000000.
     * Не смешивать Uncertain с Bad: UA-клиенты (arOPC в т.ч.) различают их.
     */
    private StatusCode statusCodeFor(String quality) {
        if (quality != null && quality.startsWith("Good")) {
            return StatusCode.GOOD;
        }
        if (quality != null && quality.startsWith("Uncertain")) {
            return new StatusCode(STATUS_UNCERTAIN);
        }
        return new StatusCode(STATUS_BAD);
    }

    private NodeId getOpcUaDataType(String javaDataType) {
        return switch (javaDataType.toUpperCase()) {
            case "BOOLEAN"  -> Identifiers.Boolean;
            case "BYTE"     -> Identifiers.Byte;
            case "SBYTE"    -> Identifiers.SByte;
            case "INT16"    -> Identifiers.Int16;
            case "UINT16"   -> Identifiers.UInt16;
            case "INT32"    -> Identifiers.Int32;
            case "UINT32"   -> Identifiers.UInt32;
            case "FLOAT"    -> Identifiers.Float;
            case "DOUBLE"   -> Identifiers.Double;
            case "STRING"   -> Identifiers.String;
            case "DATETIME" -> Identifiers.DateTime;
            default         -> Identifiers.BaseDataType;
        };
    }

    private class CustomNamespace extends ManagedNamespaceWithLifecycle {
        protected CustomNamespace(OpcUaServer server, String namespaceUri) {
            super(server, namespaceUri);
        }

        @Override
        public void onDataItemsCreated(List<DataItem> dataItems) {

        }

        @Override
        public void onDataItemsModified(List<DataItem> dataItems) { }

        @Override
        public void onDataItemsDeleted(List<DataItem> dataItems) { }

        @Override
        public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) { }
    }
}