package com.logobjectjson.model;

import java.util.LinkedHashMap;

/**
 * JSON object embedded in the log ({"key": value}). Distinguished from a
 * Java Map ({key=value}) only to display it differently in the tree.
 */
public class JsonObjectMap extends LinkedHashMap<String, Object> {
}
