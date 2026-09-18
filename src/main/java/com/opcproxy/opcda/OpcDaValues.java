package com.opcproxy.opcda;

import org.jinterop.dcom.core.JIUnsignedByte;
import org.jinterop.dcom.core.JIUnsignedInteger;
import org.jinterop.dcom.core.JIUnsignedShort;
import org.jinterop.dcom.core.JIVariant;

import java.lang.reflect.Method;

/** Конвертеры значений и качества OPC DA. Используются поллером и подписками. */
public final class OpcDaValues {

    private OpcDaValues() { }

    /** Распаковка JIVariant / JIUnsigned* в стандартные Java-типы (Da Client §4.3, ошибка №8). */
    public static Object unwrapVariant(Object raw) {
        if (raw == null) return null;
        if (raw instanceof JIVariant variant) {
            try {
                return unwrapVariant(variant.getObject());
            } catch (Exception e) {
                return raw;
            }
        }
        if (raw instanceof JIUnsignedByte b) return b.getValue();
        if (raw instanceof JIUnsignedShort s) return s.getValue();
        if (raw instanceof JIUnsignedInteger i) return i.getValue();
        if (raw.getClass().getName().startsWith("org.jinterop.dcom.core.JIUnsigned")) {
            try {
                Method getValue = raw.getClass().getMethod("getValue");
                return getValue.invoke(raw);
            } catch (Exception ignore) { }
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