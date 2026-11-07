package com.logobjectjson;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;

import java.io.IOException;
import java.io.InputStream;
import java.text.MessageFormat;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.prefs.Preferences;

/**
 * UI texts (captions, tooltips, messages), one YAML file per language:
 * {@code i18n/messages_<lang>.yml}. Keys missing in a language fall back to
 * the default language (application.yml: {@code i18n.default}). The available
 * languages are listed in {@code i18n.languages}; the chosen one is remembered.
 */
public final class Messages {

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static final Preferences PREFS = Preferences.userNodeForPackage(Messages.class);
    private static final String PREF_LANGUAGE = "language";

    private static volatile String language;
    private static volatile Map<String, String> texts;
    private static volatile Map<String, String> fallback;

    static {
        load(PREFS.get(PREF_LANGUAGE, defaultLanguage()));
    }

    private Messages() { }

    /** Languages offered in the UI (codes such as "en", "es"…), in order. */
    public static List<String> languages() {
        return Arrays.stream(Config.string("i18n.languages").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
    }

    public static String defaultLanguage() {
        return Config.string("i18n.default");
    }

    /** Current language code. */
    public static String language() {
        return language;
    }

    /** Changes (and remembers) the language; the UI must be rebuilt to show it. */
    public static void setLanguage(String lang) {
        load(lang);
        PREFS.put(PREF_LANGUAGE, language);
    }

    /** A text in a given language (e.g. each language's own name for the selector). */
    public static String in(String lang, String key) {
        String v = read(lang).get(key);
        return v != null ? v : get(key);
    }

    /** Text for {@code key}, with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    public static String get(String key, Object... args) {
        String pattern = texts.get(key);
        if (pattern == null) pattern = fallback.get(key);
        if (pattern == null) throw new IllegalStateException("Missing message key: " + key);
        if (args.length == 0) return pattern;
        // Apostrophes are literal in our texts (French, Italian…): escape them for MessageFormat
        return new MessageFormat(pattern.replace("'", "''"), Locale.forLanguageTag(language)).format(args);
    }

    /** Whether the key exists in that language's own file (used by the tests). */
    public static boolean has(String lang, String key) {
        return read(lang).containsKey(key);
    }

    private static void load(String lang) {
        String def = defaultLanguage();
        if (!languages().contains(lang)) lang = def;
        fallback = read(def);
        texts = lang.equals(def) ? fallback : read(lang);
        language = lang;
    }

    private static Map<String, String> read(String lang) {
        String resource = "/" + Config.string("i18n.resource").replace("{lang}", lang);
        var out = new HashMap<String, String>();
        try (InputStream in = Messages.class.getResourceAsStream(resource)) {
            if (in == null) return out;
            flatten("", YAML.readTree(in), out);
        } catch (IOException ex) {
            throw new IllegalStateException("Invalid messages file: " + resource, ex);
        }
        return out;
    }

    /** Keys may be written flat ("a.b": x) or nested (a: {b: x}). */
    private static void flatten(String prefix, JsonNode node, Map<String, String> out) {
        if (node == null || node.isNull()) return;
        if (node.isObject()) {
            node.fields().forEachRemaining(e ->
                    flatten(prefix.isEmpty() ? e.getKey() : prefix + "." + e.getKey(), e.getValue(), out));
        } else {
            out.put(prefix, node.asText());
        }
    }
}
