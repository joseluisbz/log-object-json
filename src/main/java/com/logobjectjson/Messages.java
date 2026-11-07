package com.logobjectjson;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * UI texts (captions, tooltips, messages) from {@code messages.properties}.
 * Adding {@code messages_<lang>.properties} translates the UI without code changes.
 */
public final class Messages {

    private static final ResourceBundle BUNDLE = ResourceBundle.getBundle("messages");

    private Messages() { }

    /** Text for {@code key}, with {@code {0}}, {@code {1}}… replaced by {@code args}. */
    public static String get(String key, Object... args) {
        String pattern;
        try {
            pattern = BUNDLE.getString(key);
        } catch (MissingResourceException ex) {
            throw new IllegalStateException("Missing message key: " + key, ex);
        }
        return args.length == 0 ? pattern : MessageFormat.format(pattern, args);
    }
}
