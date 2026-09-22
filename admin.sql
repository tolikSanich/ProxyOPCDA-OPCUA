-- 1. Проверяем, есть ли пользователь
SELECT id, username, role, enabled, password_hash FROM user_account WHERE username = 'admin';

-- 2. Если нет, или хэш неверный, выполняем UPSERT (вставка или обновление)
INSERT INTO user_account (username, password_hash, role, enabled, created_at, updated_at)
VALUES (
           'admin',
           '$2a$10$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZL', -- Хэш для "admin"
           'ADMIN',
           TRUE,
           NOW(),
           NOW()
       )
ON CONFLICT (username)
    DO UPDATE SET
                  password_hash = EXCLUDED.password_hash,
                  role = EXCLUDED.role,
                  enabled = EXCLUDED.enabled,
                  updated_at = NOW();

-- 3. Добавляем тестового оператора (пароль: "operator")
INSERT INTO user_account (username, password_hash, role, enabled, created_at, updated_at)
VALUES (
           'operator',
           '$2a$10$8K1p/a0dhrxiowP.dnkgNORTWgdEDHn5L2/xjpEWuC.QQv4rKO9jO', -- Хэш для "operator"
           'USER',
           TRUE,
           NOW(),
           NOW()
       )
ON CONFLICT (username) DO NOTHING;