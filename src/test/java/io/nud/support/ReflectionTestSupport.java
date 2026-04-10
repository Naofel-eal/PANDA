package io.nud.support;

import java.lang.reflect.Field;

public final class ReflectionTestSupport {

    private ReflectionTestSupport() {
    }

    public static void setField(Object target, String fieldName, Object value) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                Field field = type.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException ignored) {
                type = type.getSuperclass();
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to set field " + fieldName, e);
            }
        }
        throw new IllegalArgumentException("Unknown field " + fieldName + " on " + target.getClass().getName());
    }
}
