package com.logobjectjson

import com.logobjectjson.model.DynamicObject
import com.logobjectjson.parser.LogExtractor
import com.logobjectjson.ui.JsonSerializer
import com.logobjectjson.ui.fold.JsonPath
import spock.lang.Specification
import spock.lang.Unroll

/**
 * Random test: generates random objects, prints them as Lombok @ToString
 * (or Map/List.toString) would, parses them and compares with the original.
 * Fixed seed per iteration → reproducible.
 */
class FuzzSpec extends Specification {

    @Unroll
    def "random object #seed: print → parse → same value; valid JSON; paths round-trip"() {
        given:
        def rnd = new Random(seed)
        def original = root(rnd)
        def text = '2026-01-01 INFO [thread] Prefix: ' + render(original)

        when:
        def parsed = LogExtractor.extractAll(text)[0].parsed()

        then: 'the parsed value is equivalent to the original'
        normalize(parsed) == normalize(original)

        and: 'it serializes to valid JSON'
        def json = JsonSerializer.toPrettyJson(parsed)
        JsonSerializer.validate(json) == null

        and: 'every JSON path round-trips'
        paths(normalize(parsed), []).every { p ->
            int off = JsonPath.offsetOf(json, p)
            off >= 0 && JsonPath.pathAt(json, off) == p
        }

        where:
        seed << (1..60)
    }

    // ── Generation ──────────────────────────────────────────────────────────

    private static final List<String> CLASSES = ['Alpha', 'BetaDto', 'Gamma_X', 'Item', 'Node$Child']
    private static final List<String> KEYS = ['id', 'name', 'snake_case', 'camelCase', 'UP', 'v2', 'a_b__c']

    /** Main object: always a NON-empty container (what a log would print as an object). */
    private static Object root(Random r) {
        while (true) {
            def v = [object(r, 0), map(r, 0), list(r, 0)][r.nextInt(3)]
            if ((v instanceof DynamicObject && !v.properties().isEmpty())
                    || (v instanceof Map && !v.isEmpty()) || (v instanceof List && !v.isEmpty())) return v
        }
    }

    private static Object generate(Random r, int level) {
        int type = level >= 3 ? 0 : r.nextInt(level == 0 ? 3 : 6)
        switch (type) {
            case 0: case 3: return leaf(r)
            case 1: return list(r, level)
            case 4: return map(r, level)
            default: return object(r, level)
        }
    }

    private static DynamicObject object(Random r, int level) {
        def props = new LinkedHashMap<String, Object>()
        (0..<(r.nextInt(4) + 1)).each { props[KEYS[r.nextInt(KEYS.size())] + it] = generate(r, level + 1) }
        new DynamicObject(CLASSES[r.nextInt(CLASSES.size())].replace('$', ''), props)
    }

    private static List list(Random r, int level) { (0..<r.nextInt(4)).collect { generate(r, level + 1) } }

    private static Map map(Random r, int level) {
        def m = new LinkedHashMap<String, Object>()
        (0..<r.nextInt(3)).each { m['k' + it] = generate(r, level + 1) }
        m
    }

    private static Object leaf(Random r) {
        switch (r.nextInt(7)) {
            case 0: return r.nextInt(1000) - 500
            case 1: return (long) Integer.MAX_VALUE + r.nextInt(1000) + 1
            case 2: return Math.round(r.nextDouble() * 10000) / 100.0d
            case 3: return r.nextBoolean()
            case 4: return null
            case 5: return 'text' + r.nextInt(99) + '-x.y'      // number followed by text
            default: return r.nextInt(99) + '/' + r.nextInt(12) + '/2026'
        }
    }

    // ── toString()-style printing ───────────────────────────────────────────

    private static String render(Object v) {
        if (v instanceof DynamicObject) {
            return v.className() + '(' + v.properties().collect { k, x -> k + '=' + render(x) }.join(', ') + ')'
        }
        if (v instanceof Map) return '{' + v.collect { k, x -> k + '=' + render(x) }.join(', ') + '}'
        if (v instanceof List) return '[' + v.collect { render(it) }.join(', ') + ']'
        return String.valueOf(v)
    }

    // ── Comparison ──────────────────────────────────────────────────────────

    /** Comparable form: objects → [class, props]; numbers → BigDecimal. */
    private static Object normalize(Object v) {
        if (v instanceof DynamicObject) return ['@' + v.className(), normalize(v.properties())]
        if (v instanceof Map) return v.collectEntries { k, x -> [(String.valueOf(k)): normalize(x)] }
        if (v instanceof List) return v.collect { normalize(it) }
        if (v instanceof Number) return new BigDecimal(v.toString())
        return v
    }

    /** JSON paths of every key/element (on the normalized form). */
    private static List<List<Object>> paths(Object v, List<Object> base) {
        def out = []
        if (v instanceof List && v.size() == 2 && v[0] instanceof String && v[0].startsWith('@')) {
            return paths(v[1], base)                         // normalized object
        }
        if (v instanceof Map) {
            v.each { k, x -> def p = base + [k]; out << p; out.addAll(paths(x, p)) }
        } else if (v instanceof List) {
            v.eachWithIndex { x, i -> def p = base + [i]; out << p; out.addAll(paths(x, p)) }
        }
        out
    }
}
