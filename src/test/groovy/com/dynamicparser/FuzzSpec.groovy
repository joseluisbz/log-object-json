package com.dynamicparser

import com.dynamicparser.model.DynamicObject
import com.dynamicparser.parser.LogExtractor
import com.dynamicparser.ui.JsonSerializer
import com.dynamicparser.ui.fold.JsonPath
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Prueba aleatoria: genera objetos al azar, los imprime como lo haría
 * Lombok @ToString (o Map/List.toString), los parsea y compara con el original.
 * Semilla fija por iteración → reproducible.
 */
class FuzzSpec extends Specification {

    @Unroll
    def "objeto aleatorio #semilla: imprimir → parsear → mismo valor; JSON válido; rutas ida/vuelta"() {
        given:
        def rnd = new Random(semilla)
        def original = raiz(rnd)
        def texto = '2026-01-01 INFO [hilo] Prefijo: ' + imprimir(original)

        when:
        def parsed = LogExtractor.extractAll(texto)[0].parsed()

        then: 'el valor parseado es equivalente al original'
        normalizar(parsed) == normalizar(original)

        and: 'serializa a JSON válido'
        def json = JsonSerializer.toPrettyJson(parsed)
        JsonSerializer.validate(json) == null

        and: 'cada ruta del JSON hace ida y vuelta'
        rutas(normalizar(parsed), []).every { r ->
            int off = JsonPath.offsetOf(json, r)
            off >= 0 && JsonPath.pathAt(json, off) == r
        }

        where:
        semilla << (1..60)
    }

    // ── Generación ──────────────────────────────────────────────────────────

    private static final List<String> CLASES = ['Alfa', 'BetaDto', 'Gamma_X', 'Item', 'Nodo$Hijo']
    private static final List<String> CLAVES = ['id', 'nombre', 'snake_case', 'camelCase', 'UP', 'v2', 'a_b__c']

    /** Objeto principal: siempre un contenedor NO vacío (lo que un log imprimiría como objeto). */
    private static Object raiz(Random r) {
        while (true) {
            def v = [objeto(r, 0), mapa(r, 0), lista(r, 0)][r.nextInt(3)]
            if ((v instanceof DynamicObject && !v.properties().isEmpty())
                    || (v instanceof Map && !v.isEmpty()) || (v instanceof List && !v.isEmpty())) return v
        }
    }

    private static Object generar(Random r, int nivel) {
        int tipo = nivel >= 3 ? 0 : r.nextInt(nivel == 0 ? 3 : 6)
        switch (tipo) {
            case 0: case 3: return hoja(r)
            case 1: return lista(r, nivel)
            case 4: return mapa(r, nivel)
            default: return objeto(r, nivel)
        }
    }

    private static DynamicObject objeto(Random r, int nivel) {
        def props = new LinkedHashMap<String, Object>()
        (0..<(r.nextInt(4) + 1)).each { props[CLAVES[r.nextInt(CLAVES.size())] + it] = generar(r, nivel + 1) }
        new DynamicObject(CLASES[r.nextInt(CLASES.size())].replace('$', ''), props)
    }

    private static List lista(Random r, int nivel) { (0..<r.nextInt(4)).collect { generar(r, nivel + 1) } }

    private static Map mapa(Random r, int nivel) {
        def m = new LinkedHashMap<String, Object>()
        (0..<r.nextInt(3)).each { m['k' + it] = generar(r, nivel + 1) }
        m
    }

    private static Object hoja(Random r) {
        switch (r.nextInt(7)) {
            case 0: return r.nextInt(1000) - 500
            case 1: return (long) Integer.MAX_VALUE + r.nextInt(1000) + 1
            case 2: return Math.round(r.nextDouble() * 10000) / 100.0d
            case 3: return r.nextBoolean()
            case 4: return null
            case 5: return 'texto' + r.nextInt(99) + '-x.y'      // número seguido de texto incluido abajo
            default: return r.nextInt(99) + '/' + r.nextInt(12) + '/2026'
        }
    }

    // ── Impresión estilo toString() ─────────────────────────────────────────

    private static String imprimir(Object v) {
        if (v instanceof DynamicObject) {
            return v.className() + '(' + v.properties().collect { k, x -> k + '=' + imprimir(x) }.join(', ') + ')'
        }
        if (v instanceof Map) return '{' + v.collect { k, x -> k + '=' + imprimir(x) }.join(', ') + '}'
        if (v instanceof List) return '[' + v.collect { imprimir(it) }.join(', ') + ']'
        return String.valueOf(v)
    }

    // ── Comparación ─────────────────────────────────────────────────────────

    /** Lleva a una forma comparable: objetos → [clase, props]; números → BigDecimal. */
    private static Object normalizar(Object v) {
        if (v instanceof DynamicObject) return ['@' + v.className(), normalizar(v.properties())]
        if (v instanceof Map) return v.collectEntries { k, x -> [(String.valueOf(k)): normalizar(x)] }
        if (v instanceof List) return v.collect { normalizar(it) }
        if (v instanceof Number) return new BigDecimal(v.toString())
        return v
    }

    /** Rutas JSON de todas las claves/elementos (sobre la forma normalizada). */
    private static List<List<Object>> rutas(Object v, List<Object> base) {
        def out = []
        if (v instanceof List && v.size() == 2 && v[0] instanceof String && v[0].startsWith('@')) {
            return rutas(v[1], base)                         // objeto normalizado
        }
        if (v instanceof Map) {
            v.each { k, x -> def r = base + [k]; out << r; out.addAll(rutas(x, r)) }
        } else if (v instanceof List) {
            v.eachWithIndex { x, i -> def r = base + [i]; out << r; out.addAll(rutas(x, r)) }
        }
        out
    }
}
