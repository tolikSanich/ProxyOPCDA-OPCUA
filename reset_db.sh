#!/bin/bash

# ============================================
# Скрипт: пересоздание БД PostgreSQL и загрузка CSV
# ============================================

set -e

# --- Параметры подключения (можно переопределить через переменные окружения) ---
PG_USER="${PGUSER:-postgres}"
PG_HOST="${PGHOST:-localhost}"
PG_PORT="${PGPORT:-5432}"

PSQL="psql -U $PG_USER -h $PG_HOST -p $PG_PORT"

# --- 1. Запрос имени БД ---
read -p "Введите название БД: " DB_NAME

if [ -z "$DB_NAME" ]; then
    echo "Ошибка: название БД не может быть пустым."
    exit 1
fi

# --- 2. Форсированное удаление и создание БД ---
echo ">>> Удаляю БД '$DB_NAME' (форсированно)..."
$PSQL -d postgres -v ON_ERROR_STOP=1 -c "DROP DATABASE IF EXISTS \"$DB_NAME\" WITH (FORCE);"

echo ">>> Создаю БД '$DB_NAME'..."
$PSQL -d postgres -v ON_ERROR_STOP=1 -c "CREATE DATABASE \"$DB_NAME\";"

echo ">>> БД '$DB_NAME' успешно пересоздана."

# --- 3. Запрос на загрузку данных ---
read -p "Загрузить данные из CSV? (true/false): " LOAD_DATA

if [ "$LOAD_DATA" != "true" ]; then
    echo ">>> БД создана без данных. Завершение."
    exit 0
fi

# --- 4. Цикл ввода имени CSV-файла ---
CSV_FILE=""
while true; do
    read -p "Введите имя CSV-файла (или 'exit' для выхода): " INPUT_FILE

    if [ "$INPUT_FILE" = "exit" ]; then
        echo ">>> Выход без загрузки данных."
        exit 0
    fi

    # Если файл не найден по указанному пути — пробуем как файл в текущем каталоге
    if [ -f "$INPUT_FILE" ]; then
        CSV_FILE="$INPUT_FILE"
    elif [ -f "./$INPUT_FILE" ]; then
        CSV_FILE="./$INPUT_FILE"
    else
        echo "!!! Файл '$INPUT_FILE' не найден. Попробуйте снова."
        continue
    fi

    # Проверка расширения
    if [[ ! "$CSV_FILE" =~ \.csv$ ]]; then
        echo "!!! Формат файла не подходит (ожидается расширение .csv)."
        continue
    fi

    # Дополнительная проверка: файл не пустой
    if [ ! -s "$CSV_FILE" ]; then
        echo "!!! Файл пустой. Попробуйте другой файл."
        continue
    fi

    break
done

# --- 5. Загрузка CSV в БД ---
# Имя таблицы = имя файла без расширения, с заменой недопустимых символов
TABLE_NAME=$(basename "$CSV_FILE" .csv | sed 's/[^a-zA-Z0-9_]/_/g')

# Если имя начинается с цифры — добавим префикс
if [[ "$TABLE_NAME" =~ ^[0-9] ]]; then
    TABLE_NAME="t_$TABLE_NAME"
fi

echo ">>> Создаю таблицу '$TABLE_NAME' на основе заголовков CSV..."

# Читаем заголовки (первая строка)
HEADER=$(head -n 1 "$CSV_FILE")
# Убираем CR (на случай Windows-переносов)
HEADER="${HEADER//$'\r'/}"

IFS=',' read -ra COLS <<< "$HEADER"

COL_DEFS=""
for col in "${COLS[@]}"; do
    # Очищаем имя столбца
    col_clean=$(echo "$col" | tr -d '"' | sed 's/^[[:space:]]*//;s/[[:space:]]*$//' | sed 's/[^a-zA-Z0-9_]/_/g')
    [ -z "$col_clean" ] && col_clean="col"
    if [[ "$col_clean" =~ ^[0-9] ]]; then
        col_clean="c_$col_clean"
    fi
    if [ -z "$COL_DEFS" ]; then
        COL_DEFS="\"$col_clean\" TEXT"
    else
        COL_DEFS="$COL_DEFS, \"$col_clean\" TEXT"
    fi
done

$PSQL -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "CREATE TABLE \"$TABLE_NAME\" ($COL_DEFS);"

# Абсолютный путь для \copy
ABS_PATH=$(realpath "$CSV_FILE")

echo ">>> Загружаю данные из '$CSV_FILE'..."
$PSQL -d "$DB_NAME" -v ON_ERROR_STOP=1 -c "\\copy \"$TABLE_NAME\" FROM '$ABS_PATH' WITH (FORMAT csv, HEADER true);"

echo ">>> Успешно! Данные загружены в таблицу '$TABLE_NAME' БД '$DB_NAME'."
exit 0