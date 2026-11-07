package com.logobjectjson.parser

import com.logobjectjson.Config
import com.logobjectjson.model.DynamicObject
import com.logobjectjson.ui.JsonSerializer
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Path

/**
 * Configurable dialects (dialects.yml): Java, C#, Python and Rust.
 * Synthetic data; each case mimics what the language prints by default.
 */
class DialectsSpec extends Specification {

    @TempDir
    Path folder

    def cleanup() {
        System.clearProperty('lojson.dialects.file')
        Config.reload()
        Dialect.reload()
    }

    private static Object parse(String text, String dialect) {
        new ObjectParser(text, Dialect.byName(dialect)).parse()
    }

    private static LogExtractor.ParsedEntry extract(String line) {
        LogExtractor.extractAll(line)[0]
    }

    /** Comparable form: objects → [@Type: props]. */
    private static Object flat(Object v) {
        if (v instanceof DynamicObject) return ['@' + v.className(), flat(v.properties())]
        if (v instanceof Map) return v.collectEntries { k, x -> [(String.valueOf(k)): flat(x)] }
        if (v instanceof List) return v.collect { flat(it) }
        v
    }

    // ── Loading the dialects file ──────────────────────────────────────────

    def "dialects.yml (not application.yml) defines java, csharp, python and rust; java is the default"() {
        expect:
        Config.string('dialects.file') == 'dialects.yml'
        Dialect.actives()*.name() == ['java', 'csharp', 'python', 'rust']
        Dialect.defaultDialect().name() == 'java'
        Dialect.byName('python').nulls() == ['None'] as Set
    }

    def "a user file replaces, adds and disables dialects by name"() {
        given:
        def own = folder.resolve('my-dialects.yml')
        own.toFile().text = '''
dialects:
  - name: rust
    active: false
  - name: python
    nulls: [ "None", "nothing" ]
  - name: kotlin
    nulls: [ "null" ]
    trues: [ "true" ]
    falses: [ "false" ]
    separators: "="
    quotes: "\\""
    parenObject: true
'''
        System.setProperty('lojson.dialects.file', own.toAbsolutePath().toString())

        when:
        Config.reload()
        Dialect.reload()

        then:
        Dialect.actives()*.name() == ['java', 'csharp', 'python', 'kotlin']
        Dialect.byName('python').nulls() == ['None', 'nothing'] as Set
        Dialect.byName('python').quotes() == "'\""            // inherited from the original
        !Dialect.byName('rust').active()
        (parse('P(x=nothing)', 'python') as DynamicObject).properties() == [x: null]
    }

    // ── Python ──────────────────────────────────────────────────────────────

    def "Python: dataclass with single quotes, None, True/False"() {
        expect:
        flat(parse("Person(name='Ann', age=3, active=True, retired=False, extra=None)", 'python')) ==
                ['@Person', [name: 'Ann', age: 3, active: true, retired: false, extra: null]]
    }

    def "Python: dict with ':' and quoted keys, list, tuple and set"() {
        expect:
        flat(parse("{'k': 1, \"j\": [1, 'x'], 't': (1, 2), 's': {3, 4}, 'v': {}}", 'python')) ==
                [k: 1, j: [1, 'x'], t: [1, 2], s: [3, 4], v: [:]]
    }

    def "Python: positional arguments and qualified names (datetime.date(2026, 1, 1))"() {
        expect:
        flat(parse("Event(date=datetime.date(2026, 1, 1), point=Point(1, 2))", 'python')) ==
                ['@Event', [date: ['@datetime.date', ['0': 2026, '1': 1, '2': 1]],
                            point: ['@Point', ['0': 1, '1': 2]]]]
    }

    def "Python: escaped quotes and quotes of the other kind"() {
        expect:
        flat(parse("""P(a='it\\'s', b="said 'hi'", c='x,y)')""", 'python')) ==
                ['@P', [a: "it's", b: "said 'hi'", c: 'x,y)']]
    }

    // ── C# ──────────────────────────────────────────────────────────────────

    def "C#: record with braces, nested, True/False"() {
        expect:
        flat(parse('Person { Name = Ann, Age = 3, Active = True, Addr = Address { Street = Main, Number = 5 } }', 'csharp')) ==
                ['@Person', [Name: 'Ann', Age: 3, Active: true,
                             Addr: ['@Address', [Street: 'Main', Number: 5]]]]
    }

    def "C#: anonymous type { A = 1 } and ValueTuple (1, x)"() {
        expect:
        flat(parse('{ A = 1, B = free text, T = (1, x) }', 'csharp')) == [A: 1, B: 'free text', T: [1, 'x']]
    }

    // ── Rust ────────────────────────────────────────────────────────────────

    def "Rust: struct with ':', None, Some(x), tuple struct, tuple, char and vector"() {
        expect:
        flat(parse('Person { name: "Ann", age: 3, active: true, extra: None, v: Some(5), ' +
                "point: Point(1, 2), t: (1, \"x\"), c: 'z', tags: [\"a\", \"b\"] }", 'rust')) ==
                ['@Person', [name: 'Ann', age: 3, active: true, extra: null, v: ['@Some', ['0': 5]],
                             point: ['@Point', ['0': 1, '1': 2]], t: [1, 'x'], c: 'z', tags: ['a', 'b']]]
    }

    def "Rust: enum variants with a path (Color::Red) stay text; struct with a path is an object"() {
        expect:
        flat(parse('Order { color: Color::Red, state: state::State { active: true } }', 'rust')) ==
                ['@Order', [color: 'Color::Red', state: ['@state::State', [active: true]]]]
    }

    // ── Automatic dialect detection in log lines ───────────────────────────

    @Unroll
    def "detects dialect #expected in a log line with a prefix"() {
        when:
        def e = extract('2026-01-01 12:00:00 INFO [thread-1] Service: ' + object)

        then:
        e.dialect() == expected
        e.endOffset() > e.baseOffset()

        where:
        expected | object
        'java'   | 'Person(name=Ann, age=3, extra=null, addr=Addr(street=Main))'
        'java'   | 'Point[x=1, y=2]'
        'csharp' | 'Person { Name = Ann, Age = 3, Active = True }'
        'python' | "Person(name='Ann', age=3, extra=None)"
        'python' | "{'k': 1, 'list': [1, 2], 'nil': None}"
        'rust'   | 'Person { name: "Ann", age: 3, extra: None }'
    }

    def "ambiguity without hints (Foo(a=1)): the first in the list (java) wins"() {
        expect:
        extract('Foo(a=1, b=x)').dialect() == 'java'
    }

    def "spans (positions) work in other dialects"() {
        given:
        def line = "INFO P: Person(name='Ann', addr=Addr(street='Main'))"
        def e = extract(line)

        expect:
        e.dialect() == 'python'
        e.pathAt(line.indexOf('Main')) == ['addr', 'street']
        line.substring(e.baseOffset(), e.endOffset()) == "Person(name='Ann', addr=Addr(street='Main'))"
    }

    @Unroll
    def "what #dialect parses serializes to valid JSON"() {
        expect:
        JsonSerializer.validate(JsonSerializer.toPrettyJson(extract(text).parsed())) == null

        where:
        dialect  | text
        'csharp' | 'Person { Name = Ann, Tags = (1, 2), Addr = Addr { Street = Main } }'
        'python' | "P(a=None, b={'k': (1, 2)}, c={1, 2}, d=datetime.date(2026, 1, 1))"
        'rust'   | 'P { a: Some(Point(1, 2)), b: None, c: [(1, "x")] }'
    }

    // ── Random test per dialect ─────────────────────────────────────────────

    @Unroll
    def "random #dialect (seed #seed): print with its syntax → parse → same value"() {
        given:
        def rnd = new Random(seed)
        def original = object(rnd, 0)
        def line = '2026 INFO [x] ' + render(original, dialect)

        when:
        def e = extract(line)

        then:
        flat(e.parsed()) == flat(original)

        where:
        [dialect, seed] << [['csharp', 'python', 'rust'], (1..15)].combinations()
    }

    private static final List<String> CLASSES = ['Alpha', 'BetaDto', 'Item']

    private static DynamicObject object(Random r, int level) {
        def props = new LinkedHashMap<String, Object>()
        (0..r.nextInt(3)).each { props['field' + it] = value(r, level + 1) }
        new DynamicObject(CLASSES[r.nextInt(CLASSES.size())], props)
    }

    private static Object value(Random r, int level) {
        int t = level >= 3 ? r.nextInt(5) : r.nextInt(7)
        switch (t) {
            case 0: return r.nextInt(1000) - 500
            case 1: return r.nextBoolean()
            case 2: return null
            case 3: return 'text' + r.nextInt(50)
            case 4: return Math.round(r.nextDouble() * 1000) / 10.0d
            case 5: return object(r, level)
            default: return (0..<r.nextInt(3)).collect { value(r, level + 1) }
        }
    }

    /** Prints as the language would (C# record, Python repr, Rust Debug). */
    private static String render(Object v, String d) {
        if (v instanceof DynamicObject) {
            def props = v.properties().collect { k, x ->
                d == 'python' ? "$k=${render(x, d)}" : d == 'csharp' ? "$k = ${render(x, d)}" : "$k: ${render(x, d)}"
            }.join(', ')
            return d == 'python' ? "${v.className()}($props)" : "${v.className()} { $props }"
        }
        if (v instanceof List) return '[' + v.collect { render(it, d) }.join(', ') + ']'
        if (v == null) return d == 'csharp' ? 'null' : 'None'
        if (v instanceof Boolean) return d == 'rust' ? v.toString() : (v ? 'True' : 'False')
        if (v instanceof String) return d == 'csharp' ? v : (d == 'python' ? "'$v'" : "\"$v\"")
        String.valueOf(v)
    }
}
