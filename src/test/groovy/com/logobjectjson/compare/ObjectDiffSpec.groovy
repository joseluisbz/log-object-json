package com.logobjectjson.compare

import com.logobjectjson.compare.ObjectDiff.Status
import com.logobjectjson.parser.ObjectParser
import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Specification
import spock.lang.Unroll

/** Pattern vs sample comparison (synthetic data). */
class ObjectDiffSpec extends Specification {

    private static Object parse(String s) { new ObjectParser(s).parse() }

    /** path → status of every non-SAME node. */
    private static Map<String, Status> diffs(String pattern, String sample) {
        def out = [:]
        def walk
        walk = { ObjectDiff.Node n, String path ->
            if (n.status() != Status.SAME) out[path] = n.status()
            n.children().each { c -> walk(c, path ? "$path.${c.segment()}" : "${c.segment()}") }
        }
        walk(ObjectDiff.compare(parse(pattern), parse(sample)), '')
        out
    }

    def "equal objects: no differences"() {
        expect:
        diffs('A(x=1, l=[a, b], m={k=v})', 'A(x=1, l=[a, b], m={k=v})').isEmpty()
    }

    def "missing, extra, type and value differences"() {
        expect:
        diffs('A(id=1, name=Ann, active=true, tags=[a], nested=B(z=1))',
              'A(id=2, active="yes", tags=[a, b], nested=B(z=1), extra=x)') == [
                'id'     : Status.VALUE,      // 1 → 2
                'name'   : Status.MISSING,    // only in the pattern
                'active' : Status.TYPE,       // boolean → string
                'tags.1' : Status.EXTRA,      // list compared by position
                'extra'  : Status.EXTRA,
        ]
    }

    @Unroll
    def "kind changes are type differences: #p → #s"() {
        expect:
        diffs("A(v=$p)", "A(v=$s)") == [v: Status.TYPE]

        where:
        p        | s
        '1'      | '"1"'
        'null'   | 'x'
        'true'   | '1'
        '[1]'    | '{k=1}'
        'B(x=1)' | '[1]'
    }

    def "property names are case-sensitive"() {
        expect:
        diffs('A(name=x)', 'A(Name=x)') == [name: Status.MISSING, Name: Status.EXTRA]
    }

    def "a different class name is a type difference, and its properties are still compared"() {
        expect:
        diffs('A(p=Order(id=1))', 'A(p=OrderDto(id=2))') == [p: Status.TYPE, 'p.id': Status.VALUE]
    }

    def "numbers are compared by value (1 == 1.0), not by representation"() {
        expect:
        diffs('A(x=1, y=2.50)', 'A(x=1.0, y=2.5)').isEmpty()
    }

    def "a missing subtree marks all its descendants as missing"() {
        expect:
        diffs('A(p=B(x=1, y=[1]))', 'A()') == [p: Status.MISSING, 'p.x': Status.MISSING, 'p.y': Status.MISSING, 'p.y.0': Status.MISSING]
    }

    def "merged key order: sample order, pattern-only keys where they are in the pattern"() {
        expect:
        ObjectDiff.mergedKeys([a: 1, b: 2, c: 3, d: 4], [a: 1, c: 3, e: 5]) == ['a', 'b', 'c', 'e', 'd']
    }

    def "comparison JSON: valid JSON with the sample's values and the missing lines interleaved"() {
        given:
        def diff = ObjectDiff.compare(parse('A(a=1, b=2, c=B(x=1))'), parse('A(a=9, c=B(x=1), d=true)'))

        when:
        def r = MergedJson.of(diff)
        def lines = r.text().readLines()

        then: 'valid JSON, union of properties'
        new ObjectMapper().readTree(r.text()).fieldNames().toList() == ['a', 'b', 'c', 'd']
        new ObjectMapper().readTree(r.text()).get('a').asInt() == 9            // the sample's value

        and: 'each difference points to its lines'
        def byPath = r.marks().collectEntries { [(it.path().join('.')): it] }
        byPath.a.status() == Status.VALUE && lines[byPath.a.firstLine()].contains('"a": 9')
        byPath.b.status() == Status.MISSING && lines[byPath.b.firstLine()].contains('"b": 2')
        byPath.d.status() == Status.EXTRA && lines[byPath.d.firstLine()].contains('"d": true')
        !byPath.containsKey('c')
    }
}
