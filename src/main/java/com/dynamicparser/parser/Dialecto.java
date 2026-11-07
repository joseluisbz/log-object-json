package com.dynamicparser.parser;

import com.dynamicparser.Config;
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
 * Reglas de un "dialecto" de impresión de objetos (Java, C#, Python, Rust…),
 * definidas en {@code dialectos.yml} (NO en application.yml). Ver ese archivo
 * para la descripción de cada campo.
 */
public record Dialecto(
        String nombre,
        boolean activo,
        Set<String> nulos,
        Set<String> verdaderos,
        Set<String> falsos,
        String separadores,
        String comillas,
        boolean objetoParentesis,
        boolean objetoCorchetes,
        boolean objetoLlaves,
        boolean posicionales,
        boolean tuplas,
        boolean conjuntos,
        boolean jsonEmbebido) {

    public boolean esSeparador(char c) { return separadores.indexOf(c) >= 0; }

    public boolean esComilla(char c) { return comillas.indexOf(c) >= 0; }

    // ─────────────────────────────────────────────────────────────────────
    // Carga desde YAML
    // ─────────────────────────────────────────────────────────────────────

    private static final ObjectMapper YAML = new ObjectMapper(new YAMLFactory());
    private static volatile List<Dialecto> cache;

    /** Dialectos activos, en orden de prioridad. */
    public static List<Dialecto> activos() {
        return todos().stream().filter(Dialecto::activo).toList();
    }

    /** Dialecto por nombre (activo o no); excepción si no existe. */
    public static Dialecto porNombre(String nombre) {
        return todos().stream().filter(d -> d.nombre().equals(nombre)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Dialecto desconocido: " + nombre));
    }

    /** El primero de la lista: el que usa el parser cuando no se indica otro. */
    public static Dialecto porDefecto() {
        return todos().get(0);
    }

    public static List<Dialecto> todos() {
        var c = cache;
        if (c == null) cache = c = cargar();
        return c;
    }

    /** Vuelve a leer los archivos (útil en pruebas o tras editarlos). */
    public static void recargar() {
        cache = null;
    }

    /**
     * Orden de carga (por nombre, cada fuente reemplaza/agrega dialectos):
     *   1. recurso del classpath {@code dialectos.recurso} (los valores por defecto)
     *   2. ~/.parser-dinamico/&lt;archivo&gt;
     *   3. ./&lt;archivo&gt;  (o la ruta tal cual si {@code dialectos.archivo} es absoluta)
     */
    private static List<Dialecto> cargar() {
        String archivo = Config.texto("dialectos.archivo");
        var porNombre = new LinkedHashMap<String, Dialecto>();
        String recurso = "/" + Config.texto("dialectos.recurso");
        try (InputStream in = Dialecto.class.getResourceAsStream(recurso)) {
            if (in != null) leer(in, porNombre);
        } catch (IOException ex) {
            throw new IllegalStateException("Archivo de dialectos por defecto inválido: " + recurso, ex);
        }
        var rutas = new ArrayList<Path>();
        Path p = Path.of(archivo);
        if (p.isAbsolute()) {
            rutas.add(p);
        } else {
            rutas.add(Path.of(System.getProperty("user.home"), ".parser-dinamico", archivo));
            rutas.add(p);
        }
        for (Path ruta : rutas) {
            if (Files.isRegularFile(ruta)) {
                try (InputStream in = Files.newInputStream(ruta)) {
                    leer(in, porNombre);
                } catch (IOException ex) {
                    System.err.println("⚠ No se pudo leer " + ruta + ": " + ex.getMessage());
                }
            }
        }
        if (porNombre.isEmpty()) throw new IllegalStateException("No hay dialectos definidos en " + archivo);
        return List.copyOf(porNombre.values());
    }

    private static void leer(InputStream in, Map<String, Dialecto> destino) throws IOException {
        JsonNode raiz = YAML.readTree(in);
        JsonNode lista = raiz == null ? null : raiz.get("dialectos");
        if (lista == null || !lista.isArray()) return;
        for (JsonNode n : lista) {
            var d = desdeYaml(n, destino.get(texto(n, "nombre", null)));
            destino.put(d.nombre(), d);
        }
    }

    /** Construye un dialecto; los campos ausentes se heredan del que reemplaza (si existe). */
    private static Dialecto desdeYaml(JsonNode n, Dialecto base) {
        String nombre = texto(n, "nombre", null);
        if (nombre == null || nombre.isBlank()) throw new IllegalStateException("Dialecto sin 'nombre'");
        return new Dialecto(
                nombre,
                bool(n, "activo", base == null || base.activo()),
                conjunto(n, "nulos", base == null ? Set.of() : base.nulos()),
                conjunto(n, "verdaderos", base == null ? Set.of() : base.verdaderos()),
                conjunto(n, "falsos", base == null ? Set.of() : base.falsos()),
                texto(n, "separadores", base == null ? "=" : base.separadores()),
                texto(n, "comillas", base == null ? "\"" : base.comillas()),
                bool(n, "objetoParentesis", base != null && base.objetoParentesis()),
                bool(n, "objetoCorchetes", base != null && base.objetoCorchetes()),
                bool(n, "objetoLlaves", base != null && base.objetoLlaves()),
                bool(n, "posicionales", base != null && base.posicionales()),
                bool(n, "tuplas", base != null && base.tuplas()),
                bool(n, "conjuntos", base != null && base.conjuntos()),
                bool(n, "jsonEmbebido", base != null && base.jsonEmbebido()));
    }

    private static String texto(JsonNode n, String campo, String porDefecto) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? porDefecto : v.asText();
    }

    private static boolean bool(JsonNode n, String campo, boolean porDefecto) {
        JsonNode v = n.get(campo);
        return v == null || v.isNull() ? porDefecto : v.asBoolean();
    }

    private static Set<String> conjunto(JsonNode n, String campo, Set<String> porDefecto) {
        JsonNode v = n.get(campo);
        if (v == null || v.isNull()) return porDefecto;
        var s = new LinkedHashSet<String>();
        if (v.isArray()) v.forEach(x -> s.add(x.asText()));
        else s.add(v.asText());
        return Set.copyOf(s);
    }
}
