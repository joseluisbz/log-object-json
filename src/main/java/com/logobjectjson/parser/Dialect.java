package com.logobjectjson.parser;

import com.logobjectjson.Config;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Rules of an object-printing "dialect" (Java, C#, Python, Rust…), defined in
 * {@code dialects.yml} (NOT in application.yml). See that file for a
 * description of each field.
 */
public record Dialect(
        String name,
        boolean active,
        Set<String> nulls,
        Set<String> trues,
        Set<String> falses,
        String separators,
        String quotes,
        boolean parenObject,
        boolean bracketObject,
        boolean braceObject,
        boolean positional,
        boolean tuples,
        boolean sets,
        boolean embeddedJson,
        List<String> languages) {

    public boolean isSeparator(char c) { return separators.indexOf(c) >= 0; }

    public boolean isQuote(char c) { return quotes.indexOf(c) >= 0; }

    // ─────────────────────────────────────────────────────────────────────
    // Loading from YAML
    // ─────────────────────────────────────────────────────────────────────

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static volatile List<Dialect> cache;

    /** Active dialects, in priority order. */
    public static List<Dialect> actives() {
        return all().stream().filter(Dialect::active).toList();
    }

    /** Dialect by name (active or not); throws if it does not exist. */
    public static Dialect byName(String name) {
        return all().stream().filter(d -> d.name().equals(name)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown dialect: " + name));
    }

    /** The first in the list: the one the parser uses when none is given. */
    public static Dialect defaultDialect() {
        return all().get(0);
    }

    public static List<Dialect> all() {
        var c = cache;
        if (c == null) cache = c = load();
        return c;
    }

    /** Re-reads the files (useful in tests or after editing them). */
    public static void reload() {
        cache = null;
    }

    /**
     * Load order (by name, each source replaces/adds dialects):
     *   1. classpath resource {@code dialects.resource} (the defaults)
     *   2. ~/.log-object-json/&lt;file&gt;
     *   3. ./&lt;file&gt;  (or the path as is, if {@code dialects.file} is absolute)
     */
    private static List<Dialect> load() {
        String file = Config.string("dialects.file");
        var byName = new LinkedHashMap<String, Dialect>();
        String resource = "/" + Config.string("dialects.resource");
        try (InputStream in = Dialect.class.getResourceAsStream(resource)) {
            if (in != null) read(in, byName);
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid default dialects file: " + resource, ex);
        }
        var paths = new ArrayList<Path>();
        Path p = Path.of(file);
        if (p.isAbsolute()) {
            paths.add(p);
        } else {
            paths.add(Path.of(System.getProperty("user.home"), Config.USER_DIR, file));
            paths.add(p);
        }
        for (Path path : paths) {
            if (Files.isRegularFile(path)) {
                try (InputStream in = Files.newInputStream(path)) {
                    read(in, byName);
                } catch (IOException ex) {
                    System.err.println("⚠ Could not read " + path + ": " + ex.getMessage());
                }
            }
        }
        if (byName.isEmpty()) throw new IllegalStateException("No dialects defined in " + file);
        return List.copyOf(byName.values());
    }

    private static void read(InputStream in, Map<String, Dialect> target) throws IOException {
        JsonNode root = YAML.readTree(in);
        JsonNode list = root == null ? null : root.get("dialects");
        if (list == null || !list.isArray()) return;
        for (JsonNode n : list) {
            var d = fromYaml(n, target.get(text(n, "name", null)));
            target.put(d.name(), d);
        }
    }

    /** Builds a dialect; missing fields are inherited from the one it replaces (if any). */
    private static Dialect fromYaml(JsonNode n, Dialect base) {
        String name = text(n, "name", null);
        if (name == null || name.isBlank()) throw new IllegalStateException("Dialect without 'name'");
        return new Dialect(
                name,
                bool(n, "active", base == null || base.active()),
                set(n, "nulls", base == null ? Set.of() : base.nulls()),
                set(n, "trues", base == null ? Set.of() : base.trues()),
                set(n, "falses", base == null ? Set.of() : base.falses()),
                text(n, "separators", base == null ? "=" : base.separators()),
                text(n, "quotes", base == null ? "\"" : base.quotes()),
                bool(n, "parenObject", base != null && base.parenObject()),
                bool(n, "bracketObject", base != null && base.bracketObject()),
                bool(n, "braceObject", base != null && base.braceObject()),
                bool(n, "positional", base != null && base.positional()),
                bool(n, "tuples", base != null && base.tuples()),
                bool(n, "sets", base != null && base.sets()),
                bool(n, "embeddedJson", base != null && base.embeddedJson()),
                list(n, "languages", base == null ? List.of(name) : base.languages()));
    }

    private static String text(JsonNode n, String field, String fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asText();
    }

    private static boolean bool(JsonNode n, String field, boolean fallback) {
        JsonNode v = n.get(field);
        return v == null || v.isNull() ? fallback : v.asBoolean();
    }

    private static List<String> list(JsonNode n, String field, List<String> fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        var out = new ArrayList<String>();
        if (v.isArray()) v.forEach(x -> out.add(x.asText()));
        else out.add(v.asText());
        return List.copyOf(out);
    }

    private static Set<String> set(JsonNode n, String field, Set<String> fallback) {
        JsonNode v = n.get(field);
        if (v == null || v.isNull()) return fallback;
        var s = new LinkedHashSet<String>();
        if (v.isArray()) v.forEach(x -> s.add(x.asText()));
        else s.add(v.asText());
        return Set.copyOf(s);
    }
}
