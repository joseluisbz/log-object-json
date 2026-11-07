package com.logobjectjson.parser

import com.logobjectjson.model.ArrayRef
import com.logobjectjson.model.DynamicObject
import com.logobjectjson.model.JsonObjectMap
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/**
 * toString()/JSON parser. All data is synthetic: the app must not depend on
 * any particular log.
 */
class ObjectParserSpec extends Specification {

    private static Object parse(String s) { new ObjectParser(s).parse() }

    def "Lombok-style object: Type(key=value, ...)"() {
        when:
        def o = parse('Foo(a=1, b=x)') as DynamicObject

        then:
        o.className() == 'Foo'
        o.properties() == [a: 1, b: 'x']
    }

    def "Java record: Type[key=value, ...]"() {
        when:
        def o = parse('Wrap(p=Point[x=1, y=2])') as DynamicObject

        then:
        def p = o.properties().p as DynamicObject
        p.className() == 'Point'
        p.properties() == [x: 1, y: 2]
    }

    def "Java Map {k=v} and JSON object {\"k\": v} are told apart"() {
        expect:
        parse('{k=v, n=3}') instanceof Map
        !(parse('{k=v, n=3}') instanceof JsonObjectMap)
        parse('{"k": 1, "l": [true]}') instanceof JsonObjectMap
        parse('{"k": 1, "l": [true]}') == [k: 1, l: [true]]
    }

    @Unroll
    def "empty containers: #input"() {
        expect:
        size(parse(input)) == 0

        where:
        input << ['[]', '{}', 'Foo()', 'Point[]']
    }

    private static int size(Object v) {
        if (v instanceof DynamicObject) return v.properties().size()
        if (v instanceof Map) return v.size()
        return (v as Collection).size()
    }

    @Unroll
    def "array printed without contents #raw → #type"() {
        when:
        def o = parse("Foo(a=$raw)") as DynamicObject

        then:
        def ref = o.properties().a as ArrayRef
        ref.javaType() == type
        ref.raw() == raw

        where:
        raw                          || type
        '[Ljava.lang.String;@1b6d'   || 'String[]'
        '[[I@7a81'                   || 'int[][]'
        '[Lcom.x.y.Item$Sub;@ff'     || 'Item$Sub[]'
        '[J@1'                       || 'long[]'
    }

    @Unroll
    def "number '#raw' → #type"() {
        expect:
        def v = (parse("Foo(v=$raw)") as DynamicObject).properties().v
        v.getClass() == type
        v == expected

        where:
        raw          || type    | expected
        '10'         || Integer | 10
        '-5'         || Integer | -5
        '3000000000' || Long    | 3000000000L
        '1.5'        || Double  | 1.5d
        '1.4E-4'     || Double  | 1.4E-4d
        '0.0001446'  || Double  | 1.446E-4d
    }

    @Unroll
    def "a number followed by text is a whole String: '#raw'"() {
        expect:
        (parse("Foo(v=$raw, w=1)") as DynamicObject).properties() == [v: raw, w: 1]

        where:
        raw << ['123-report.pdf', '22/10/2026', '1.0.3', '5 years', '10kg', '-3x']
    }

    def "special values: null, booleans, dash and empty"() {
        expect:
        (parse('Foo(a=null, b=true, c=false, d=-, e=, f="")') as DynamicObject).properties() ==
                [a: null, b: true, c: false, d: '-', e: '', f: '']
    }

    def "free text with spaces and colons is kept"() {
        expect:
        (parse('Foo(date=Fri Nov 10 21:00:00 CLST 2000, z=1)') as DynamicObject).properties().date ==
                'Fri Nov 10 21:00:00 CLST 2000'
    }

    @Unroll
    def "keys in varied formats are preserved: #key"() {
        expect:
        (parse("Foo($key=1)") as DynamicObject).properties().keySet() == [key] as Set

        where:
        key << ['camelCase', 'snake_case', 'custom_field__c', 'UPPER', 'a.b', 'a-b', '$x', 'x1']
    }

    def "JSON strings with escapes, commas and braces"() {
        expect:
        parse('{"a": "x\\"y,}", "b": "\\u00e1"}') == [a: 'x"y,}', b: 'á']
    }

    @Timeout(5)
    def "deep nesting (#levels levels) without overflow"() {
        given:
        def text = ('A(b=' * levels) + '1' + (')' * levels)

        when:
        def o = parse(text)
        int depth = 0
        while (o instanceof DynamicObject) { o = o.properties().b; depth++ }

        then:
        depth == levels
        o == 1

        where:
        levels << [1, 20, 200]
    }

    @Unroll
    def "malformed text does not hang: '#text'"() {
        when:
        parse(text)

        then:
        noExceptionThrown()

        where:
        text << ['Foo(a=1, b=', '{a=[1, 2}', 'Foo(a=Bar(x=', '[[[', '"unclosed', 'Foo(=1)']
    }

    def "spans cover key → end of value, with their path"() {
        given:
        def text = 'Foo(a=1, b=[x, Bar(c=2)])'
        def p = new ObjectParser(text)

        when:
        p.parse()
        def byPath = p.spans.collectEntries { [(it.path()): text.substring(it.start(), it.end())] }

        then:
        byPath[['a']] == 'a=1'
        byPath[['b']] == 'b=[x, Bar(c=2)]'
        byPath[['b', 0]] == 'x'
        byPath[['b', 1]] == 'Bar(c=2)'
        byPath[['b', 1, 'c']] == 'c=2'
    }
}
