package com.dynamicparser.ui

import com.dynamicparser.model.ArrayRef
import com.dynamicparser.model.DynamicObject
import com.dynamicparser.parser.ObjectParser
import com.dynamicparser.ui.fold.JsonPath
import spock.lang.Specification
import spock.lang.Unroll

/** Serialización JSON (formato Postman) y rutas JSON (texto ↔ ruta). */
class JsonSpec extends Specification {

    def "formato estilo Postman: sangría 4, 'k': v, vacíos compactos"() {
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

    def "un DynamicObject se serializa con sus propiedades (sin la clase); ArrayRef como texto"() {
        given:
        def o = new DynamicObject('Foo', [p: new DynamicObject('Bar', [x: 1]), arr: new ArrayRef('int[]', '[I@1')])

        expect:
        JsonSerializer.toPrettyJson(o).replaceAll(/\s+/, '') == '{"p":{"x":1},"arr":"[I@1"}'
    }

    @Unroll
    def "todo lo parseado serializa a JSON válido: #texto"() {
        expect:
        JsonSerializer.validate(JsonSerializer.toPrettyJson(new ObjectParser(texto).parse())) == null

        where:
        texto << ['A(x=1)', '{k=[1, {z=B(q="a,b")}]}', '[[], {}, A()]', '{"j": [1, {"k": null}]}',
                  'R[a=1, b=[I@2]', 'A(t=Fri Nov 10 21:00:00 CLST 2000)']
    }

    def "validate informa línea y columna del error"() {
        expect:
        JsonSerializer.validate('{\n  "a": \n}') =~ /línea 3/
    }

    def "ida y vuelta: para cada clave/elemento, pathAt(offsetOf(ruta)) == ruta"() {
        given:
        def json = JsonSerializer.toPrettyJson(new ObjectParser(
                'P(id=1, lineas=[L(prod=A, tags=[x, y]), L(prod=B, tags=[])], extra={"k k": {z=2}})').parse())
        def rutas = [
                ['id'], ['lineas'], ['lineas', 0], ['lineas', 0, 'prod'], ['lineas', 0, 'tags', 1],
                ['lineas', 1, 'tags'], ['extra'], ['extra', 'k k'], ['extra', 'k k', 'z'],
        ]

        expect:
        rutas.every { r ->
            int off = JsonPath.offsetOf(json, r)
            off >= 0 && JsonPath.pathAt(json, off) == r
        }
        JsonPath.offsetOf(json, ['noExiste']) == -1
    }

    @Unroll
    def "format: #ruta → #texto"() {
        expect:
        JsonPath.format(ruta) == texto

        where:
        ruta                       || texto
        ['a', 'b']                 || 'a.b'
        ['a', 0, 'c']              || 'a[0].c'
        ['a b', 1]                 || '["a b"][1]'
        []                         || ''
    }

    def "format con marcas (p. ej. <,> en claves que son Map)"() {
        expect:
        JsonPath.format(['m', 'x', 0], { i -> i == 0 ? '<,>' : null }) == '<,>m.x[0]'
    }
}
