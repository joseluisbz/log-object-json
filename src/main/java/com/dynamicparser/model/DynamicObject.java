package com.dynamicparser.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Representa un objeto Java instanciado dinámicamente desde su representación toString().
 * Preserva los nombres de propiedades exactamente como aparecen
 * (camelCase, PascalCase, snake_case, UPPER_CASE, etc.).
 */
public record DynamicObject(String className, Map<String, Object> properties) {

    public DynamicObject(String className) {
        this(className, new LinkedHashMap<>());
    }

    @Override
    public String toString() {
        return className + properties;
    }
}
