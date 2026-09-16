#!/bin/bash

# Скрипт для объединения файлов с поддержкой пути и имен файлов

# Цвета для вывода
GREEN='\033[0;32m'
BLUE='\033[0;34m'
YELLOW='\033[1;33m'
RED='\033[0;31m'
NC='\033[0m' # No Color

# Функция для вывода справки
show_help() {
    echo "Использование: $0 [ПУТЬ] [ИМЕНА_ФАЙЛОВ]"
    echo ""
    echo "Объединяет файлы из указанной папки в один файл"
    echo ""
    echo "Аргументы:"
    echo "  ПУТЬ            Путь для поиска файлов (по умолчанию: src/main/)"
    echo "  ИМЕНА_ФАЙЛОВ    Имена файлов для объединения (разделитель: запятая)"
    echo "                  Если не указаны, объединяются все файлы"
    echo ""
    echo "Опции:"
    echo "  -o, --output ФАЙЛ      Выходной файл (по умолчанию: merged_output.txt)"
    echo "  -e, --ext РАСШИРЕНИЕ   Фильтр по расширению (например: java, txt)"
    echo "  -r, --recursive         Рекурсивный поиск (по умолчанию включен)"
    echo "  -n, --no-recursive      Без рекурсии"
    echo "  -p, --prefix            Добавлять префикс с именем файла"
    echo "  -d, --dry-run           Только показать, что будет объединено"
    echo "  -h, --help              Показать эту справку"
    echo ""
    echo "Примеры:"
    echo "  $0                                      # Поиск в src/main/, все файлы"
    echo "  $0 src/main/java                        # Поиск в src/main/java"
    echo "  $0 src/ \"Main.java,Config.java\"       # Только указанные файлы"
    echo "  $0 . \"*.java,*.xml\"                  # Все .java и .xml файлы"
    echo "  $0 -e java -o all.java                 # Все .java файлы в all.java"
    echo "  $0 src/ -e java -p                     # .java файлы с префиксом"
    exit 0
}

# Функция для парсинга имен файлов
parse_file_names() {
    local names="$1"
    local result=()

    # Разделяем по запятой и удаляем пробелы
    IFS=',' read -ra parts <<< "$names"
    for part in "${parts[@]}"; do
        # Удаляем лишние пробелы
        part=$(echo "$part" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
        if [[ -n "$part" ]]; then
            result+=("$part")
        fi
    done

    # Выводим как строку с разделителями для find
    if [[ ${#result[@]} -gt 0 ]]; then
        # Создаем условие для find
        local condition=""
        for name in "${result[@]}"; do
            if [[ "$name" == *"*"* ]]; then
                # Если есть wildcard, используем -name
                if [[ -z "$condition" ]]; then
                    condition="-name \"$name\""
                else
                    condition="$condition -o -name \"$name\""
                fi
            else
                # Точное имя файла
                if [[ -z "$condition" ]]; then
                    condition="-name \"$name\""
                else
                    condition="$condition -o -name \"$name\""
                fi
            fi
        done
        echo "$condition"
    else
        echo ""
    fi
}

# Значения по умолчанию
SEARCH_PATH="src/main/"
FILE_NAMES=""
OUTPUT_FILE="merged_output.txt"
EXTENSION=""
RECURSIVE=true
PREFIX=false
DRY_RUN=false
VERBOSE=false

# Парсинг аргументов
while [[ $# -gt 0 ]]; do
    case $1 in
        -h|--help)
            show_help
            ;;
        -o|--output)
            OUTPUT_FILE="$2"
            shift 2
            ;;
        -e|--ext)
            EXTENSION="$2"
            shift 2
            ;;
        -r|--recursive)
            RECURSIVE=true
            shift
            ;;
        -n|--no-recursive)
            RECURSIVE=false
            shift
            ;;
        -p|--prefix)
            PREFIX=true
            shift
            ;;
        -d|--dry-run)
            DRY_RUN=true
            shift
            ;;
        -v|--verbose)
            VERBOSE=true
            shift
            ;;
        -*)
            echo -e "${RED}Неизвестная опция: $1${NC}"
            echo "Используйте -h для справки"
            exit 1
            ;;
        *)
            # Первый неопциональный аргумент - путь
            if [[ -z "$SEARCH_PATH_SET" ]]; then
                SEARCH_PATH="$1"
                SEARCH_PATH_SET=true
            # Второй неопциональный аргумент - имена файлов
            else
                FILE_NAMES="$1"
            fi
            shift
            ;;
    esac
done

# Проверка существования папки
if [[ ! -d "$SEARCH_PATH" ]]; then
    echo -e "${RED}Ошибка: Папка '$SEARCH_PATH' не найдена${NC}"
    echo "Создать папку? (y/N): "
    read -r answer
    if [[ "$answer" == "y" ]] || [[ "$answer" == "Y" ]]; then
        mkdir -p "$SEARCH_PATH"
        echo -e "${GREEN}Папка создана: $SEARCH_PATH${NC}"
    else
        exit 1
    fi
fi

# Формирование команды find
FIND_CMD="find \"$SEARCH_PATH\""

if [[ "$RECURSIVE" == false ]]; then
    FIND_CMD="$FIND_CMD -maxdepth 1"
fi

# Добавляем фильтр по типу файла
FIND_CMD="$FIND_CMD -type f"

# Фильтр по расширению
if [[ -n "$EXTENSION" ]]; then
    # Убираем точку, если она есть
    EXT="${EXTENSION#.}"
    FIND_CMD="$FIND_CMD -name \"*.$EXT\""
fi

# Фильтр по именам файлов
if [[ -n "$FILE_NAMES" ]]; then
    # Проверяем, есть ли wildcard в именах
    if [[ "$FILE_NAMES" == *"*"* ]]; then
        # Используем парсинг для wildcard
        name_condition=$(parse_file_names "$FILE_NAMES")
        if [[ -n "$name_condition" ]]; then
            # Если уже есть фильтр по расширению, добавляем с условием
            if [[ -n "$EXTENSION" ]]; then
                FIND_CMD="$FIND_CMD $name_condition"
            else
                FIND_CMD="$FIND_CMD \\( $name_condition \\)"
            fi
        fi
    else
        # Точные имена файлов (разделенные запятой)
        IFS=',' read -ra names <<< "$FILE_NAMES"
        local name_condition=""
        for name in "${names[@]}"; do
            name=$(echo "$name" | sed 's/^[[:space:]]*//;s/[[:space:]]*$//')
            if [[ -n "$name" ]]; then
                if [[ -z "$name_condition" ]]; then
                    name_condition="-name \"$name\""
                else
                    name_condition="$name_condition -o -name \"$name\""
                fi
            fi
        done

        if [[ -n "$name_condition" ]]; then
            if [[ -n "$EXTENSION" ]]; then
                FIND_CMD="$FIND_CMD $name_condition"
            else
                FIND_CMD="$FIND_CMD \\( $name_condition \\)"
            fi
        fi
    fi
fi

# Сортировка
FIND_CMD="$FIND_CMD | sort"

# Вывод настроек
echo -e "${BLUE}Настройки:${NC}"
echo "  Путь поиска: $SEARCH_PATH"
echo "  Выходной файл: $OUTPUT_FILE"
[[ -n "$EXTENSION" ]] && echo "  Расширение: .$EXTENSION"
[[ -n "$FILE_NAMES" ]] && echo "  Имена файлов: $FILE_NAMES"
echo "  Рекурсивно: $RECURSIVE"
echo "  Префикс: $PREFIX"
echo "  Dry run: $DRY_RUN"
echo "----------------------------------------"

# Сбор списка файлов
if [[ "$DRY_RUN" == true ]]; then
    echo -e "${YELLOW}Файлы, которые будут объединены:${NC}"
    eval $FIND_CMD | while read -r file; do
        rel_path="${file#$SEARCH_PATH/}"
        if [[ "$rel_path" == "$file" ]]; then
            rel_path="$file"
        fi
        echo "  $rel_path"
    done

    total_files=$(eval $FIND_CMD | wc -l)
    echo "----------------------------------------"
    echo -e "${BLUE}Всего файлов: $total_files${NC}"
    exit 0
fi

# Проверка, есть ли файлы для объединения
total_files=$(eval $FIND_CMD | wc -l)
if [[ $total_files -eq 0 ]]; then
    echo -e "${RED}Ошибка: Нет файлов для объединения${NC}"
    echo ""
    echo "Советы:"
    echo "  - Проверьте путь: $SEARCH_PATH"
    echo "  - Проверьте расширение: $EXTENSION"
    echo "  - Проверьте имена файлов: $FILE_NAMES"
    echo "  - Используйте -d для просмотра файлов"
    exit 1
fi

echo -e "${GREEN}Найдено $total_files файлов${NC}"

# Объединение файлов
echo -e "${BLUE}Объединение файлов...${NC}"

# Очистка выходного файла
> "$OUTPUT_FILE"

# Переменная для подсчета
count=0

# Обход и объединение файлов
eval $FIND_CMD | while read -r file; do
    count=$((count + 1))

    # Относительный путь для отображения
    rel_path="${file#$SEARCH_PATH/}"
    if [[ "$rel_path" == "$file" ]]; then
        rel_path="$file"
    fi

    # Добавление разделителя
    if [[ $count -gt 1 ]]; then
        echo "" >> "$OUTPUT_FILE"
        echo "============================================================" >> "$OUTPUT_FILE"
        echo "" >> "$OUTPUT_FILE"
    fi

    # Добавление префикса с именем файла
    if [[ "$PREFIX" == true ]]; then
        echo "// ============================================================" >> "$OUTPUT_FILE"
        echo "// Файл: $rel_path" >> "$OUTPUT_FILE"
        echo "// ============================================================" >> "$OUTPUT_FILE"
        echo "" >> "$OUTPUT_FILE"
    fi

    # Добавление содержимого файла
    cat "$file" >> "$OUTPUT_FILE"

    if [[ "$VERBOSE" == true ]]; then
        echo -e "${GREEN}[$count/$total_files] Добавлен: $rel_path${NC}"
    else
        # Показываем прогресс каждые 10 файлов
        if [[ $((count % 10)) -eq 0 ]]; then
            echo -e "${YELLOW}Обработано: $count/$total_files файлов${NC}"
        fi
    fi
done

echo "----------------------------------------"
echo -e "${GREEN}Готово!${NC}"
echo "  Объединено файлов: $total_files"
echo "  Выходной файл: $OUTPUT_FILE"
echo "  Размер: $(du -h "$OUTPUT_FILE" | cut -f1)"