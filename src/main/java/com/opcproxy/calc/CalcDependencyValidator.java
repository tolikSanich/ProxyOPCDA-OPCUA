package com.opcproxy.calc;

import com.opcproxy.persistence.entity.Tag;
import com.opcproxy.persistence.repository.TagRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.stereotype.Component;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Валидатор CALC-формул (ТЗ §5.4.5):
 *  - существование ссылок (#TagName);
 *  - обнаружение циклических зависимостей (DFS);
 *  - ограничение глубины цепочки CALC->CALC (<= 10);
 *  - запрет ссылки тега на самого себя.
 */
@Component
@RequiredArgsConstructor
public class CalcDependencyValidator {

    /** Ссылка на тег: #ИмяТега (буквы/цифры/подчёркивание). */
    private static final Pattern TAG_REF = Pattern.compile("#([A-Za-z_][A-Za-z0-9_]*)");
    private static final int MAX_DEPTH = 10;

    private final TagRepository tagRepository;

    public record ValidationResult(List<String> errors) {
        public boolean ok() { return errors.isEmpty(); }
    }

    /**
     * Полная проверка формулы тега (для UI-диалога и CSV-импорта).
     *
     * @param calcTag проверяемый CALC-тег (name + expression)
     */
    public ValidationResult validate(Tag calcTag) {
        List<String> errors = new ArrayList<>();
        String expr = calcTag.getExpression();
        if (expr == null || expr.isBlank()) {
            errors.add("Формула пуста");
            return new ValidationResult(errors);
        }
        if (expr.length() > 2000) {
            errors.add("Формула длиннее 2000 символов");
        }

        // Синтаксис — через движок (компиляция без вычисления)
        try {
            new SpelExpressionParser().parseExpression(expr);
        } catch (Exception e) {
            errors.add("Синтаксическая ошибка: " + e.getMessage());
            return new ValidationResult(errors); // дальше разбирать нечего
        }

        Map<String, Tag> byName = tagRepository.findAll().stream()
                .collect(Collectors.toMap(Tag::getName, t -> t, (a, b) -> a));

        // Ссылки
        List<String> refs = extractRefs(expr);
        if (refs.isEmpty()) {
            errors.add("Формула не содержит ссылок на теги (#ИмяТега)");
        }
        for (String ref : refs) {
            if (!byName.containsKey(ref)) {
                errors.add("Неизвестный тег в формуле: #" + ref);
            } else if (ref.equals(calcTag.getName())) {
                errors.add("Циклическая зависимость: тег ссылается сам на себя");
            }
        }
        if (!errors.isEmpty()) {
            return new ValidationResult(errors);
        }

        // Циклы и глубина: DFS от этого тега по CALC-зависимостям
        Set<String> visited = new HashSet<>();
        Deque<String> path = new ArrayDeque<>();
        dfs(calcTag.getName(), byName, visited, path, errors, 0);

        return new ValidationResult(errors);
    }

    private void dfs(String current, Map<String, Tag> byName,
                     Set<String> visited, Deque<String> path,
                     List<String> errors, int depth) {
        if (depth > MAX_DEPTH) {
            errors.add("Превышена глубина цепочки CALC-тегов (> " + MAX_DEPTH + "): "
                    + String.join(" -> ", path));
            return;
        }
        if (path.contains(current)) {
            errors.add("Циклическая зависимость: "
                    + String.join(" -> ", path) + " -> " + current);
            return;
        }
        Tag tag = byName.get(current);
        if (tag == null || tag.getSourceType() != com.opcproxy.persistence.enums.SourceType.CALC) {
            return; // DA-тег — лист графа
        }
        path.push(current);
        for (String ref : extractRefs(tag.getExpression())) {
            if (!visited.contains(ref) || path.contains(ref)) {
                dfs(ref, byName, visited, path, errors, depth + 1);
            }
        }
        visited.add(current);
        path.pop();
    }

    /** Ссылки формулы (публично — используется CalcEngineService). */
    public List<String> extractRefs(String expression) {
        if (expression == null) return List.of();
        List<String> refs = new ArrayList<>();
        Matcher m = TAG_REF.matcher(expression);
        while (m.find()) {
            refs.add(m.group(1));
        }
        return refs.stream().distinct().toList();
    }
}