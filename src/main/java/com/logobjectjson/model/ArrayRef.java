package com.logobjectjson.model;

/**
 * Reference to an array printed with the default Object.toString(),
 * e.g. {@code [Ljava.lang.String;@1b6d3586} or {@code [I@7a81197d}.
 * The type is certain, but the log does NOT contain its elements.
 *
 * @param javaType readable type, e.g. "String[]", "int[][]"
 * @param raw      original log text
 */
public record ArrayRef(String javaType, String raw) {

    @Override
    public String toString() {
        return raw;
    }
}
