package com.opcproxy.calc;

import java.util.Locale;

/**
 * Нормализация пользовательского синтаксиса (ТЗ §5.4.4):
 *   AND -> and, OR -> or, NOT -> !, XOR( -> #xor(, round(x) -> #round1(x) [один аргумент],
 *   голые имена тегов -> #ИмяТега.
 * Вызывается перед парсингом (compile), поэтому одинаково действует
 * в валидаторе, движке и CSV-импорте.
 */
public final class ExpressionNormalizer {
    private ExpressionNormalizer() {}

    public static String normalize(String expr) {
        if (expr == null || expr.isBlank()) return expr;
        String s = expr;

        // 1. Ключевые слова (вне кавычек — упрощение: формулы со строками, содержащими AND, редки)
        s = s.replaceAll("\\bAND\\b", "and");
        s = s.replaceAll("\\bOR\\b", "or");
        s = s.replaceAll("\\bNOT\\b", "!");
        s = s.replaceAll("\\bXOR\\s*\\(", "#xor(");

        // 2. round(x) с одним аргументом -> #round1(x) (аргумент без запятой до закрытия скобки)
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("round\\s*\\(([^(),]*)\\)").matcher(s);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            m.appendReplacement(sb, "#round1(" + m.group(1) + ")");
        }
        m.appendTail(sb);
        s = sb.toString();

        // 3. Голые идентификаторы -> #идентификатор.
        //    Не трогаем: уже-#теги, #функции, T(...), and/or/null/true/false, имена после точки.
        java.util.regex.Pattern ident = java.util.regex.Pattern
                .compile("(?<![#.\\w])([A-Za-z_][A-Za-z0-9_]*)");
        m = ident.matcher(s);
        sb = new StringBuffer();
        while (m.find()) {
            String word = m.group(1);
            String lower = word.toLowerCase(Locale.ROOT);
            boolean keyword = lower.equals("and") || lower.equals("or")
                    || lower.equals("t") || lower.equals("null")
                    || lower.equals("true") || lower.equals("false")
                    || lower.equals("new");
            m.appendReplacement(sb, keyword ? word : "#" + word);
        }
        m.appendTail(sb);
        return sb.toString();
    }
}