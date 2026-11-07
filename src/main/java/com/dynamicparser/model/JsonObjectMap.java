package com.dynamicparser.model;

import java.util.LinkedHashMap;

/**
 * Objeto JSON embebido en el log ({"clave": valor}). Se distingue de un
 * Map de Java ({clave=valor}) sólo para mostrarlo de forma distinta en el árbol.
 */
public class JsonObjectMap extends LinkedHashMap<String, Object> {
}
