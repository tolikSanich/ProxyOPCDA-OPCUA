-- Таблица подключений к OPC DA
CREATE TABLE opc_da_connection (
                                   id BIGSERIAL PRIMARY KEY,
                                   name VARCHAR(255) NOT NULL UNIQUE,
                                   host VARCHAR(255) NOT NULL,
                                   prog_id_or_clsid VARCHAR(255) NOT NULL,
                                   username VARCHAR(255),
                                   password_encrypted TEXT,
                                   default_read_mode VARCHAR(50) NOT NULL DEFAULT 'ASYNC',
                                   default_refresh_period_ms INT NOT NULL DEFAULT 1000,
                                   reconnect_interval_ms INT NOT NULL DEFAULT 5000,
                                   enabled BOOLEAN NOT NULL DEFAULT TRUE,
                                   description TEXT,
                                   created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                   updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Таблица профилей интервалов
CREATE TABLE interval_profile (
                                  id BIGSERIAL PRIMARY KEY,
                                  name VARCHAR(255) NOT NULL UNIQUE,
                                  ua_sampling_interval_ms INT NOT NULL DEFAULT 1000,
                                  mqtt_publish_interval_ms INT,
                                  mqtt_deadband REAL,
                                  description TEXT,
                                  created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                                  updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Таблица тегов
CREATE TABLE tag (
                     id BIGSERIAL PRIMARY KEY,
                     name VARCHAR(255) NOT NULL UNIQUE,
                     source_type VARCHAR(50) NOT NULL, -- 'DA' or 'CALC'
                     connection_id BIGINT REFERENCES opc_da_connection(id) ON DELETE SET NULL,
                     source_item_id VARCHAR(512),
                     expression TEXT,
                     data_type VARCHAR(50) NOT NULL,
                     read_mode VARCHAR(50) NOT NULL DEFAULT 'ASYNC',
                     refresh_period_ms INT NOT NULL DEFAULT 1000,
                     ua_sampling_interval_ms INT,
                     interval_profile_id BIGINT REFERENCES interval_profile(id) ON DELETE SET NULL,
                     publish_mqtt BOOLEAN NOT NULL DEFAULT FALSE,
                     mqtt_deadband REAL,
                     description TEXT,
                     enabled BOOLEAN NOT NULL DEFAULT TRUE,
                     version INT NOT NULL DEFAULT 0, -- Optimistic locking
                     created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                     updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Таблица пользователей
CREATE TABLE user_account (
                              id BIGSERIAL PRIMARY KEY,
                              username VARCHAR(255) NOT NULL UNIQUE,
                              password_hash VARCHAR(255) NOT NULL,
                              role VARCHAR(50) NOT NULL DEFAULT 'VIEWER',
                              enabled BOOLEAN NOT NULL DEFAULT TRUE,
                              created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                              updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Таблица аудита
CREATE TABLE audit_log (
                           id BIGSERIAL PRIMARY KEY,
                           timestamp TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
                           actor VARCHAR(255),
                           action VARCHAR(50) NOT NULL,
                           entity_type VARCHAR(100) NOT NULL,
                           entity_id BIGINT,
                           old_value TEXT,
                           new_value TEXT,
                           details TEXT
);

-- Таблица системных настроек
CREATE TABLE system_setting (
                                key VARCHAR(255) PRIMARY KEY,
                                value TEXT NOT NULL,
                                updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

-- Индексы для производительности
CREATE INDEX idx_tag_connection ON tag(connection_id);
CREATE INDEX idx_tag_source_type ON tag(source_type);
CREATE INDEX idx_audit_log_timestamp ON audit_log(timestamp);
CREATE INDEX idx_audit_log_entity ON audit_log(entity_type, entity_id);