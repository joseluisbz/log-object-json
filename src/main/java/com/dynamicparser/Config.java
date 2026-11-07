package com.dynamicparser;

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
 * Parámetros ajustables de la aplicación, leídos de {@code application.yml}
 * (sin valores fijos en el código).
 *
 * Orden de carga (cada fuente sobrescribe a la anterior):
 *   1. Valores por defecto: recurso {@code /application.yml} del classpath.
 *   2. {@code ~/.parser-dinamico/application.yml}  (usuario).
 *   3. {@code ./application.yml}                    (directorio de trabajo).
 *   4. Propiedades de sistema {@code -Dparser.<clave.con.puntos>=valor}.
 *
 * Las claves anidadas del YAML se acceden con puntos: {@code busqueda.esperaMs}.
 */
public final class Config {

    private static final String ARCHIVO = "application.yml";
    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static volatile Map<String, String> valores = cargar();

    private Config() { }

    /** Vuelve a leer todas las fuentes (útil en pruebas o tras editar el YAML). */
    public static void recargar() {
        valores = cargar();
    }

    private static Map<String, String> cargar() {
        var m = new LinkedHashMap<String, String>();
        try (InputStream in = Config.class.getResourceAsStream("/" + ARCHIVO)) {
            if (in != null) aplanar("", YAML.readTree(in), m);
        } catch (IOException ex) {
            throw new IllegalStateException("application.yml por defecto inválido", ex);
        }
        for (Path ruta : new Path[]{
                Path.of(System.getProperty("user.home"), ".parser-dinamico", ARCHIVO),
                Path.of(ARCHIVO)}) {
            if (Files.isRegularFile(ruta)) {
                try (InputStream in = Files.newInputStream(ruta)) {
                    aplanar("", YAML.readTree(in), m);
                } catch (IOException ex) {
                    System.err.println("⚠ No se pudo leer " + ruta + ": " + ex.getMessage());
                }
            }
        }
        System.getProperties().forEach((k, v) -> {
            String clave = String.valueOf(k);
            if (clave.startsWith("parser.")) m.put(clave.substring("parser.".length()), String.valueOf(v));
        });
        return m;
    }

    /** {a: {b: 1}} → "a.b" = "1" */
    private static void aplanar(String prefijo, JsonNode nodo, Map<String, String> m) {
        if (nodo == null || nodo.isNull()) return;
        if (nodo.isObject()) {
            nodo.fields().forEachRemaining(e ->
                    aplanar(prefijo.isEmpty() ? e.getKey() : prefijo + "." + e.getKey(), e.getValue(), m));
        } else {
            m.put(prefijo, nodo.asText());
        }
    }

    public static String texto(String clave) {
        String v = valores.get(clave);
        if (v == null) throw new IllegalStateException("Falta la clave de configuración: " + clave);
        return v.trim();
    }

    public static int entero(String clave) {
        return Integer.parseInt(texto(clave));
    }
}
