package com.logobjectjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Tunable application parameters, read from {@code application.yml}
 * (no hard-coded values in the code).
 *
 * Load order (each source overrides the previous one):
 *   1. Defaults: classpath resource {@code /application.yml}.
 *   2. {@code ~/.log-object-json/application.yml}  (user).
 *   3. {@code ./application.yml}                    (working directory).
 *   4. System properties {@code -Dlojson.<dotted.key>=value}.
 *
 * Nested YAML keys are accessed with dots: {@code search.delayMs}.
 */
public final class Config {

    /** Per-user folder for configuration overrides. */
    public static final String USER_DIR = ".log-object-json";
    private static final String FILE = "application.yml";
    private static final String SYSPROP_PREFIX = "lojson.";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static volatile Map<String, String> values = load();

    private Config() { }

    /** Re-reads every source (useful in tests or after editing the YAML). */
    public static void reload() {
        values = load();
    }

    private static Map<String, String> load() {
        var m = new LinkedHashMap<String, String>();
        try (InputStream in = Config.class.getResourceAsStream("/" + FILE)) {
            if (in != null) flatten("", YAML.readTree(in), m);
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid default application.yml", ex);
        }
        for (Path path : new Path[]{
                Path.of(System.getProperty("user.home"), USER_DIR, FILE),
                Path.of(FILE)}) {
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    flatten("", YAML.readTree(in), m);
                } catch (IOException ex) {
                    System.err.println("⚠ Could not read " + path + ": " + ex.getMessage());
                }
            }
        }
        System.getProperties().forEach((k, v) -> {
            String key = String.valueOf(k);
            if (key.startsWith(SYSPROP_PREFIX)) m.put(key.substring(SYSPROP_PREFIX.length()), String.valueOf(v));
        });
        return m;
    }

    /** {a: {b: 1}} → "a.b" = "1" */
    private static void flatten(String prefix, JsonNode node, Map<String, String> m) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), m));
        } else {
            m.put(prefix, node.asText());
        }
    }

    public static String string(String key) {
        String v = values.get(key);
        if (v == null) throw new IllegalStateException("Missing configuration key: " + key);
        return v.trim();
    }

    public static int integer(String key) {
        return Integer.parseInt(string(key));
    }
}
