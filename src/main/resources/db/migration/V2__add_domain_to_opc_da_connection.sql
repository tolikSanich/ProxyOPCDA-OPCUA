-- 1. Добавляем колонку, временно разрешая NULL и задавая значение по умолчанию
ALTER TABLE opc_da_connection
    ADD COLUMN IF NOT EXISTS domain VARCHAR(255) DEFAULT '';

-- 2. Принудительно обновляем все существующие записи, чтобы в них гарантированно была пустая строка
UPDATE opc_da_connection
SET domain = ''
WHERE domain IS NULL;

-- 3. Теперь, когда все записи заполнены, мы можем безопасно добавить ограничение NOT NULL
ALTER TABLE opc_da_connection
    ALTER COLUMN domain SET NOT NULL;