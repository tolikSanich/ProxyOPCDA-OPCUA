#!/usr/bin/env bash
# tree.sh — построение дерева папок и файлов
# Работает: Linux, macOS, Windows (Git Bash / MSYS2 / Cygwin / WSL).
#
# Использование:
#   ./tree.sh [источник] [recursive:true/false] [макс_глубина] [файл_вывода]

set -u

# ---------- Определяем ОС (только для итогового сообщения) ----------
case "$(uname -s 2>/dev/null || echo unknown)" in
  Linux*)               OS="Linux" ;;
  Darwin*)              OS="macOS" ;;
  MINGW*|MSYS*|CYGWIN*) OS="Windows (Git Bash/MSYS)" ;;
  *)                    OS="Unknown" ;;
esac

# ---------- Аргументы ----------
SRC="${1:-}"
RECURSIVE="${2:-}"
MAX_DEPTH="${3:-}"
OUTPUT="${4:-}"

# ---------- Интерактивные вопросы ----------
if [ -z "$SRC" ]; then
  read -rp "Укажите корневую папку: " SRC
fi
if [ -z "$SRC" ] || [ ! -d "$SRC" ]; then
  echo "Ошибка: папка-источник не найдена: '$SRC'" >&2
  exit 1
fi

if [ -z "$RECURSIVE" ]; then
  read -rp "Нужен ли рекурсивный обход? (true/false) [true]: " RECURSIVE
  RECURSIVE="${RECURSIVE:-true}"
fi
case "$RECURSIVE" in
  true|yes|y|1|да)   RECURSIVE=true  ;;
  false|no|n|0|нет)  RECURSIVE=false ;;
  *) echo "Ошибка: ожидается true/false, получено '$RECURSIVE'." >&2; exit 1 ;;
esac

if [ "$RECURSIVE" = true ] && [ -z "$MAX_DEPTH" ]; then
  read -rp "Сколько уровней вглубь (Enter — без ограничения): " MAX_DEPTH
fi
if [ -z "$MAX_DEPTH" ]; then
  MAX_DEPTH=999999
fi
case "$MAX_DEPTH" in
  ''|*[!0-9]*) echo "Ошибка: глубина должна быть целым числом." >&2; exit 1 ;;
esac

if [ -z "$OUTPUT" ]; then
  read -rp "Имя файла вывода [tree.txt]: " OUTPUT
  OUTPUT="${OUTPUT:-tree.txt}"
fi

# ---------- Готовим выходной файл ----------
: > "$OUTPUT" || { echo "Не удалось создать файл: $OUTPUT" >&2; exit 1; }

# ---------- Обход ----------
walk() {
  local dir="$1"
  local depth="$2"
  local indent
  indent=$(printf '%*s' $((depth * 2)) '')

  # Все элементы каталога, включая скрытые (кроме . и ..),
  # отсортированные, с корректной обработкой пробелов/юникода.
  local entries=()
  while IFS= read -r -d '' e; do
    entries+=("$e")
  done < <(find "$dir" -mindepth 1 -maxdepth 1 -print0 2>/dev/null | LC_ALL=C sort -z)

  local e name
  for e in "${entries[@]}"; do
    name=$(basename "$e")
    if [ -d "$e" ]; then
      printf '%s%s/\n' "$indent" "$name" >> "$OUTPUT"
      if [ "$RECURSIVE" = true ] && [ "$depth" -lt "$MAX_DEPTH" ]; then
        walk "$e" $((depth + 1))
      fi
    else
      printf '%s%s\n' "$indent" "$name" >> "$OUTPUT"
    fi
  done
}

# ---------- Корень и запуск ----------
# Абсолютный путь корня (нормализует слеши на Windows)
SRC_ABS=$(cd "$SRC" && pwd -P)
ROOT_NAME=$(basename "$SRC_ABS")

printf '%s/\n' "$ROOT_NAME" >> "$OUTPUT"
walk "$SRC_ABS" 1

echo "[$OS] Готово: $OUTPUT"