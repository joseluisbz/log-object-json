package com.dynamicparser.parser

import com.dynamicparser.model.ArrayRef
import com.dynamicparser.model.DynamicObject
import com.dynamicparser.model.JsonObjectMap
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/**
 * Parser de toString()/JSON. Todos los datos son sintéticos: la app no debe
 * depender de ningún log concreto.
 */
class ObjectParserSpec extends Specification {

    private static Object parse(String s) { new ObjectParser(s).parse() }

    def "objeto estilo Lombok: Clase(clave=valor, ...)"() {
        when:
        def o = parse('Foo(a=1, b=x)') as DynamicObject

        then:
        o.className() == 'Foo'
        o.properties() == [a: 1, b: 'x']
    }

    def "record de Java: Clase[clave=valor, ...]"() {
        when:
        def o = parse('Wrap(p=Punto[x=1, y=2])') as DynamicObject

        then:
        def p = o.properties().p as DynamicObject
        p.className() == 'Punto'
        p.properties() == [x: 1, y: 2]
    }

    def "Map de Java {k=v} y objeto JSON {\"k\": v} se distinguen"() {
        expect:
        parse('{k=v, n=3}') instanceof Map
        !(parse('{k=v, n=3}') instanceof JsonObjectMap)
        parse('{"k": 1, "l": [true]}') instanceof JsonObjectMap
        parse('{"k": 1, "l": [true]}') == [k: 1, l: [true]]
    }

    @Unroll
    def "contenedores vacíos: #entrada"() {
        expect:
        tamano(parse(entrada)) == 0

        where:
        entrada << ['[]', '{}', 'Foo()', 'Punto[]']
    }

    private static int tamano(Object v) {
        if (v instanceof DynamicObject) return v.properties().size()
        if (v instanceof Map) return v.size()
        return (v as Collection).size()
    }

    @Unroll
    def "arreglo impreso sin contenido #raw → #tipo"() {
        when:
        def o = parse("Foo(a=$raw)") as DynamicObject

        then:
        def ref = o.properties().a as ArrayRef
        ref.javaType() == tipo
        ref.raw() == raw

        where:
        raw                          || tipo
        '[Ljava.lang.String;@1b6d'   || 'String[]'
        '[[I@7a81'                   || 'int[][]'
        '[Lcom.x.y.Item$Sub;@ff'     || 'Item$Sub[]'
        '[J@1'                       || 'long[]'
    }

    @Unroll
    def "número '#raw' → #clase"() {
        expect:
        def v = (parse("Foo(v=$raw)") as DynamicObject).properties().v
        v.getClass() == clase
        v == esperado

        where:
        raw          || clase   | esperado
        '10'         || Integer | 10
        '-5'         || Integer | -5
        '3000000000' || Long    | 3000000000L
        '1.5'        || Double  | 1.5d
        '1.4E-4'     || Double  | 1.4E-4d
        '0.0001446'  || Double  | 1.446E-4d
    }

    @Unroll
    def "un número seguido de texto es String completo: '#raw'"() {
        expect:
        (parse("Foo(v=$raw, w=1)") as DynamicObject).properties() == [v: raw, w: 1]

        where:
        raw << ['123-informe.pdf', '22/10/2026', '1.0.3', '5 años', '10kg', '-3x']
    }

    def "valores especiales: null, booleanos, guion y vacío"() {
        expect:
        (parse('Foo(a=null, b=true, c=false, d=-, e=, f="")') as DynamicObject).properties() ==
                [a: null, b: true, c: false, d: '-', e: '', f: '']
    }

    def "texto libre con espacios y dos puntos se conserva"() {
        expect:
        (parse('Foo(fecha=Fri Nov 10 21:00:00 CLST 2000, z=1)') as DynamicObject).properties().fecha ==
                'Fri Nov 10 21:00:00 CLST 2000'
    }

    @Unroll
    def "claves con formatos variados se preservan: #clave"() {
        expect:
        (parse("Foo($clave=1)") as DynamicObject).properties().keySet() == [clave] as Set

        where:
        clave << ['camelCase', 'snake_case', 'bci_field__c', 'UPPER', 'a.b', 'a-b', '$x', 'x1']
    }

    def "strings JSON con escapes, comas y llaves"() {
        expect:
        parse('{"a": "x\\"y,}", "b": "\\u00e1"}') == [a: 'x"y,}', b: 'á']
    }

    @Timeout(5)
    def "anidamiento profundo (#niveles niveles) sin desbordar"() {
        given:
        def texto = ('A(b=' * niveles) + '1' + (')' * niveles)

        when:
        def o = parse(texto)
        int profundidad = 0
        while (o instanceof DynamicObject) { o = o.properties().b; profundidad++ }

        then:
        profundidad == niveles
        o == 1

        where:
        niveles << [1, 20, 200]
    }

    @Unroll
    def "texto mal formado no se cuelga: '#texto'"() {
        when:
        parse(texto)

        then:
        noExceptionThrown()

        where:
        texto << ['Foo(a=1, b=', '{a=[1, 2}', 'Foo(a=Bar(x=', '[[[', '"sin cierre', 'Foo(=1)']
    }

    def "los tramos (spans) cubren clave → fin del valor, con su ruta"() {
        given:
        def texto = 'Foo(a=1, b=[x, Bar(c=2)])'
        def p = new ObjectParser(texto)

        when:
        p.parse()
        def porRuta = p.spans.collectEntries { [(it.path()): texto.substring(it.start(), it.end())] }

        then:
        porRuta[['a']] == 'a=1'
        porRuta[['b']] == 'b=[x, Bar(c=2)]'
        porRuta[['b', 0]] == 'x'
        porRuta[['b', 1]] == 'Bar(c=2)'
        porRuta[['b', 1, 'c']] == 'c=2'
    }
}
