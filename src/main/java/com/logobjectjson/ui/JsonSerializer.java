package com.logobjectjson.ui;

import com.logobjectjson.model.DynamicObject;
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
 * Serializes dynamic objects to "beautified" JSON, Postman style:
 * 4-space indentation, {@code "key": value} and empty {@code []} / {@code {}}.
 */
public final class JsonSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);

    private JsonSerializer() { }

    public static String toPrettyJson(Object value) throws IOException {
        return MAPPER.writer(new PostmanPrettyPrinter()).writeValueAsString(toPlain(value));
    }

    /** Validates a JSON text; returns null if valid, otherwise the error message. */
    public static String validate(String json) {
        try {
            MAPPER.readTree(json);
            return null;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            return ex.getOriginalMessage() + locationOf(ex);
        }
    }

    /** Character offset of the first syntax error, or -1 if the JSON is valid. */
    public static int errorOffset(String json) {
        try {
            MAPPER.readTree(json);
            return -1;
        } catch (com.fasterxml.jackson.core.JsonProcessingException ex) {
            var loc = ex.getLocation();
            if (loc == null) return 0;
            if (loc.getCharOffset() >= 0) return (int) Math.min(loc.getCharOffset(), json.length());
            int line = 1, i = 0;
            while (i < json.length() && line < loc.getLineNr()) if (json.charAt(i++) == '\n') line++;
            return Math.min(json.length(), i + Math.max(0, loc.getColumnNr() - 1));
        }
    }

    private static String locationOf(com.fasterxml.jackson.core.JsonProcessingException ex) {
        var loc = ex.getLocation();
        return loc == null ? "" : " " + com.logobjectjson.Messages.get("json.location", loc.getLineNr(), loc.getColumnNr());
    }

    /** DynamicObject → Map of its properties (recursive); Map/List are copied. */
    private static Object toPlain(Object value) {
        if (value instanceof DynamicObject obj) {
            return toPlain(obj.properties());
        }
        if (value instanceof com.logobjectjson.model.ArrayRef ref) {
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

    /** Postman-style pretty printer. */
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
