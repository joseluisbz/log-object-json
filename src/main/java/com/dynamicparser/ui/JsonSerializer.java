package com.dynamicparser.ui;

import com.dynamicparser.model.DynamicObject;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Serializa los objetos dinámicos a JSON "beautified" con estilo Postman:
 * sangría de 4 espacios, {@code "key": value} y {@code []} / {@code {}} vacíos.
 */
public final class JsonSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private JsonSerializer() { }

    public static String toPrettyJson(Object value) throws IOException {
        return MAPPER.writer(new PostmanPrettyPrinter()).writeValueAsString(toPlain(value));
    }

    /** Valida un texto JSON; devuelve null si es válido o el mensaje de error. */
    public static String validate(String json) {
        try {
            MAPPER.readTree(json);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return ex.getOriginalMessage() + locationOf(ex);
        }
    }

    private static String locationOf(com.fasterxml.jackson.core.JsonProcessingException ex) {
        var loc = ex.getLocation();
        return loc == null ? "" : " (línea " + loc.getLineNr() + ", columna " + loc.getColumnNr() + ")";
    }

    /** DynamicObject → Map de sus propiedades (recursivo); Map/List se copian. */
    private static Object toPlain(Object value) {
        if (value instanceof DynamicObject obj) {
            return toPlain(obj.properties());
        }
        if (value instanceof com.dynamicparser.model.ArrayRef ref) {
            return ref.raw();
        }
        if (value instanceof Map<?, ?> map) {
            var out = new LinkedHashMap<String, Object>();
            map.forEach((k, v) -> out.put(String.valueOf(k), toPlain(v)));
            return out;
        }
        if (value instanceof List<?> list) {
            var out = new ArrayList<>(list.size());
            list.forEach(v -> out.add(toPlain(v)));
            return out;
        }
        return value;
    }

    /** Pretty printer estilo Postman. */
    private static final class PostmanPrettyPrinter extends DefaultPrettyPrinter {
        PostmanPrettyPrinter() {
            var indenter = new DefaultIndenter("    ", "\n");
            indentObjectsWith(indenter);
            indentArraysWith(indenter);
        }

        @Override
        public DefaultPrettyPrinter createInstance() {
            return new PostmanPrettyPrinter();
        }

        @Override
        public void writeObjectFieldValueSeparator(JsonGenerator g) throws IOException {
            g.writeRaw(": ");
        }

        @Override
        public void writeEndObject(JsonGenerator g, int nrOfEntries) throws IOException {
            if (!_objectIndenter.isInline()) _nesting--;
            if (nrOfEntries > 0) _objectIndenter.writeIndentation(g, _nesting);
            g.writeRaw('}');
        }

        @Override
        public void writeEndArray(JsonGenerator g, int nrOfValues) throws IOException {
            if (!_arrayIndenter.isInline()) _nesting--;
            if (nrOfValues > 0) _arrayIndenter.writeIndentation(g, _nesting);
            g.writeRaw(']');
        }
    }
}
