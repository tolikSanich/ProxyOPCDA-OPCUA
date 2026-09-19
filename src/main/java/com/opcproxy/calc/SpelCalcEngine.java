package com.opcproxy.calc;

import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.TypeLocator;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Движок вычисления CALC-тегов на SpEL (ТЗ §5.4).
 *
 * Безопасность (ТЗ §7.2):
 *  - T() резолвится ТОЛЬКО по белому списку классов (SpelWhitelistTypeLocator);
 *  - конструкторы запрещены (пустой список ConstructorResolvers);
 *  - бины (@bean) недоступны (BeanResolver не установлен);
 *  - переменные — только имена тегов (#TagName -> значение).
 *
 * ВАЖНО: compile() — чистая функция без побочных эффектов. Кэширование
 * выполняется ТОЛЬКО в evaluate(). Вложенная запись в compiledCache из
 * computeIfAbsent() бросает ConcurrentModificationException("Recursive update").
 */
@Component
public class SpelCalcEngine {
    /** Компилированное выражение + текст формулы, по которой оно получено. */
    private record CompiledExpression(String sourceText, Expression expression) {}


    /** Классы, доступные из формул через T(...). */
    private static final Set<String> ALLOWED_TYPES = Set.of(
            "java.lang.Math",
            "java.lang.Double",
            "java.lang.Integer",
            "java.lang.Long",
            "java.lang.Boolean",
            "java.lang.String",
            "java.time.Duration",
            "java.time.Instant");

    private static final int MAX_EXPRESSION_LENGTH = 2000;

    private final ExpressionParser parser = new SpelExpressionParser();
    /** Кэш скомпилированных выражений: tagId -> Expression. Пишется ТОЛЬКО из evaluate(). */
    private final Map<Long, Expression> compiledCache = new ConcurrentHashMap<>();
    // ДОБАВИТЬ рядом — тексты формул, по которым скомпилирован кэш:
    private final Map<Long, String> cacheSources = new ConcurrentHashMap<>();
    /**
     * Парсит и валидирует формулу. ЧИСТАЯ функция: не пишет в кэш.
     * Используется и для валидации (CalcDependencyValidator/TagDialog),
     * и внутри evaluate() после промаха кэша.
     */
    public Expression compile(String expression) {
        if (expression == null || expression.isBlank()) {
            throw new IllegalArgumentException("Формула пуста");
        }
        if (expression.length() > MAX_EXPRESSION_LENGTH) {
            throw new IllegalArgumentException("Формула длиннее " + MAX_EXPRESSION_LENGTH + " символов");
        }
        try {
            return parser.parseExpression(ExpressionNormalizer.normalize(expression));
        } catch (Exception e) {
            throw new IllegalArgumentException("Синтаксическая ошибка в формуле: " + e.getMessage(), e);
        }
    }

    /** Сброс кэша при изменении формулы тега (вызывать из TagService.save/delete). */
    public void invalidate(Long tagId) {
        compiledCache.remove(tagId);
    }

    /**
     * Вычисляет формулу. При изменении ТЕКСТА формулы перекопилирует автоматически
     * (защита от устаревшего кэша — аналог Excel'евского dirty-recalc).
     */
    public Object evaluate(Long tagId, String expression, Map<String, Object> values) {
        // Dirty-check: перекопиляция при изменении ТЕКСТА формулы.
        // Никаких compute/lambda — обычные get/put, конфликт типов невозможен.
        Expression expr = compiledCache.get(tagId);
        String cachedSrc = cacheSources.get(tagId);
        if (expr == null || !expression.equals(cachedSrc)) {
            expr = compile(expression);
            compiledCache.put(tagId, expr);
            cacheSources.put(tagId, expression);
        }

        StandardEvaluationContext ctx = new StandardEvaluationContext();
        try {
            ctx.registerFunction("abs",
                    CalcFunctions.class.getMethod("abs", double.class));
            ctx.registerFunction("min",
                    CalcFunctions.class.getMethod("min", double.class, double.class));
            ctx.registerFunction("max",
                    CalcFunctions.class.getMethod("max", double.class, double.class));
            ctx.registerFunction("round1",
                    CalcFunctions.class.getMethod("round", double.class));
            ctx.registerFunction("round",
                    CalcFunctions.class.getMethod("round", double.class, int.class));
            ctx.registerFunction("xor",
                    CalcFunctions.class.getMethod("xor", boolean.class, boolean.class));
        } catch (NoSuchMethodException e) {
            throw new IllegalStateException("Calc functions registration failed", e);
        }
        ctx.setTypeLocator(new SpelWhitelistTypeLocator());
        ctx.setConstructorResolvers(List.of());
        values.forEach(ctx::setVariable);

        return expr.getValue(ctx);
    }

    // ------------------------------------------------------------------

    /** TypeLocator, пропускающий только классы из белого списка (ТЗ §7.2). */
    private static class SpelWhitelistTypeLocator implements TypeLocator {
        @Override
        public Class<?> findType(String typeName) {
            if (ALLOWED_TYPES.contains(typeName)) {
                try {
                    return Class.forName(typeName);
                } catch (ClassNotFoundException e) {
                    throw new IllegalArgumentException("Класс не найден: " + typeName);
                }
            }
            throw new IllegalArgumentException(
                    "Класс '" + typeName + "' не входит в белый список формул. " +
                            "Разрешены: " + ALLOWED_TYPES);
        }
    }
}