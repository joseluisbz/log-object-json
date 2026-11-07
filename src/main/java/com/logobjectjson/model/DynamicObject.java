package com.logobjectjson.model;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * An object instantiated dynamically from its printed (toString()) form.
 * Property names are preserved exactly as they appear
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
