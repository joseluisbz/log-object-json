package com.logobjectjson.ui

import com.logobjectjson.model.ArrayRef
import com.logobjectjson.model.DynamicObject
import com.logobjectjson.parser.ObjectParser
import com.logobjectjson.ui.fold.JsonPath
import spock.lang.Specification
import spock.lang.Unroll

/** JSON serialization (Postman format) and JSON paths (text ↔ path). */
class JsonSpec extends Specification {

    def "Postman style: indent 4, 'k': v, compact empties"() {
        given:
        def o = new ObjectParser('Foo(a=1, l=[], m={}, s=x, n=null)').parse()

        expect:
        JsonSerializer.toPrettyJson(o) == '''{
    "a": 1,
    "l": [],
    "m": {},
    "s": "x",
    "n": null
}'''
    }

    def "a DynamicObject serializes its properties (without the class); ArrayRef as text"() {
        given:
        def o = new DynamicObject('Foo', [p: new DynamicObject('Bar', [x: 1]), arr: new ArrayRef('int[]', '[I@1')])

        expect:
        JsonSerializer.toPrettyJson(o).replaceAll(/\s+/, '') == '{"p":{"x":1},"arr":"[I@1"}'
    }

    @Unroll
    def "everything parsed serializes to valid JSON: #text"() {
        expect:
        JsonSerializer.validate(JsonSerializer.toPrettyJson(new ObjectParser(text).parse())) == null

        where:
        text << ['A(x=1)', '{k=[1, {z=B(q="a,b")}]}', '[[], {}, A()]', '{"j": [1, {"k": null}]}',
                 'R[a=1, b=[I@2]', 'A(t=Fri Nov 10 21:00:00 CLST 2000)']
    }

    def "validate reports the error line and column"() {
        expect:
        JsonSerializer.validate('{\n  "a": \n}') =~ /line 3/
    }

    def "round trip: for each key/element, pathAt(offsetOf(path)) == path"() {
        given:
        def json = JsonSerializer.toPrettyJson(new ObjectParser(
                'P(id=1, lines=[L(prod=A, tags=[x, y]), L(prod=B, tags=[])], extra={"k k": {z=2}})').parse())
        def paths = [
                ['id'], ['lines'], ['lines', 0], ['lines', 0, 'prod'], ['lines', 0, 'tags', 1],
                ['lines', 1, 'tags'], ['extra'], ['extra', 'k k'], ['extra', 'k k', 'z'],
        ]

        expect:
        paths.every { p ->
            int off = JsonPath.offsetOf(json, p)
            off >= 0 && JsonPath.pathAt(json, off) == p
        }
        JsonPath.offsetOf(json, ['missing']) == -1
    }

    @Unroll
    def "format: #path → #text"() {
        expect:
        JsonPath.format(path) == text

        where:
        path                       || text
        ['a', 'b']                 || 'a.b'
        ['a', 0, 'c']              || 'a[0].c'
        ['a b', 1]                 || '["a b"][1]'
        []                         || ''
    }

    def "format with marks (e.g. <,> on keys that are a Map)"() {
        expect:
        JsonPath.format(['m', 'x', 0], { i -> i == 0 ? '<,>' : null }) == '<,>m.x[0]'
    }
}
