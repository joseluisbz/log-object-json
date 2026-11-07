package com.dynamicparser.model;

/**
 * Referencia a un arreglo impreso con Object.toString() por defecto,
 * p. ej. {@code [Ljava.lang.String;@1b6d3586} o {@code [I@7a81197d}.
 * El tipo es seguro, pero el log NO contiene sus elementos.
 *
 * @param javaType tipo legible, p. ej. "String[]", "int[][]"
 * @param raw      texto original del log
 */
public record ArrayRef(String javaType, String raw) {

    @Override
    public String toString() {
        return raw;
    }
}
