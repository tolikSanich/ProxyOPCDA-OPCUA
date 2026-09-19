package com.opcproxy.opcda;

import org.jinterop.dcom.core.*;

import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Calendar;

/** Конвертеры значений и качества OPC DA. Используются поллером и подписками. */
public final class OpcDaValues {

    private OpcDaValues() { }

    /** Распаковка JIVariant / JIUnsigned* в стандартные Java-типы (Da Client §4.3, ошибка №8). */
    public static Object unwrapVariant(Object raw) {
        switch (raw) {
            case null -> {
                return null;
            }
            case JIVariant variant -> {
                try {
                    return unwrapVariant(variant.getObject());
                } catch (Exception e) {
                    return raw;
                }
            }
            case JIUnsignedByte b -> {
                return b.getValue();
            }
            case JIUnsignedShort s -> {
                return s.getValue();
            }
            case JIUnsignedInteger i -> {
                return i.getValue();
            }
            default -> {
            }
        }
        if (raw.getClass().getName().startsWith("org.jinterop.dcom.core.JIUnsigned")) {
            try {
                Method getValue = raw.getClass().getMethod("getValue");
                return getValue.invoke(raw);
            } catch (Exception ignore) { }
        }
        // JIString j-Interop'а -> обычная Java-строка (иначе Milo не сможет закодировать Variant)
        switch (raw) {
            case JIString js -> {
                try {
                    return js.getString();
                } catch (Exception e) {
                    return js.toString();
                }
            }

            // Character -> Short (DA иногда отдаёт 16-битные как char; 'Û' = 219)
            case Character c -> {
                return (short) c.charValue();
            }

            // java.util.Date / Calendar -> OffsetDateTime (builtin DateTime в Milo)
            case java.util.Date d -> {
                return new org.eclipse.milo.opcua.stack.core.types.builtin.DateTime(d);
            }
            case Calendar cal -> {
                return new org.eclipse.milo.opcua.stack.core.types.builtin.DateTime(cal.getTime());
            }
            default -> {
            }
        }
        return raw;
    }

    /** Старшие 2 бита кода качества OPC DA: 192=Good, 64=Bad, 80=Uncertain. */
    public static String parseOpcQuality(int q) {
        int status = q & 0xC0;
        if (status == 0xC0) return "Good";
        if (status == 0x40) return "Bad";
        if (status == 0x80) return "Uncertain";
        return "Bad_Unknown";
    }
}