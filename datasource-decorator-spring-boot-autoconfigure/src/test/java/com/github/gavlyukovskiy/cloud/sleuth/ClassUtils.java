package com.github.gavlyukovskiy.cloud.sleuth;

import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.Objects;

@UtilityClass
public class ClassUtils {
    static Class<?> optionalClass(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable ignore) {
            return null;
        }
    }

    static Class[] nonNullClasses(Class... classes) {
        return Arrays.stream(classes).filter(Objects::nonNull).toArray(Class[]::new);
    }
}
