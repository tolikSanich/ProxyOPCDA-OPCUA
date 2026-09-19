package com.opcproxy.rest.dto;

import com.opcproxy.opcda.ConnectionState;
import com.opcproxy.persistence.entity.OpcDaConnection;
import com.opcproxy.persistence.entity.Tag;

public final class RestDtos {

    private RestDtos() {}

    public record ConnectionDto(Long id, String name, String host, String progIdOrClsid,
                                String username, String domain, boolean enabled,
                                ConnectionState state, int errorCount) {
        public static ConnectionDto of(OpcDaConnection c, ConnectionState st, int errors) {
            return new ConnectionDto(c.getId(), c.getName(), c.getHost(), c.getProgIdOrClsid(),
                    c.getUsername(), c.getDomain(), Boolean.TRUE.equals(c.getEnabled()), st, errors);
        }
    }

    public record ConnectionCreateRequest(String name, String host, String progIdOrClsid,
                                          String username, String password, String domain,
                                          boolean enabled) {}

    public record TagDto(Long id, String name, String sourceType, String connectionName,
                         String sourceItemId, String expression, String dataType,
                         boolean enabled, Object value, String quality, String timestamp) {
        public static TagDto of(Tag t, Object value, String quality, String ts) {
            return new TagDto(t.getId(), t.getName(),
                    t.getSourceType() != null ? t.getSourceType().name() : null,
                    t.getConnection() != null ? t.getConnection().getName() : null,
                    t.getSourceItemId(), t.getExpression(),
                    t.getDataType() != null ? t.getDataType().name() : null,
                    Boolean.TRUE.equals(t.getEnabled()), value, quality, ts);
        }
    }

    public record StatusDto(int connectionsTotal, int connectionsConnected,
                            int tagsTotal, int tagsGood, String opcUaEndpoint) {}

    public record ImportResultDto(int total, int created, int updated, int errors) {}
}