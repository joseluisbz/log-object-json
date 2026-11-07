package com.dynamicparser.parser

import com.dynamicparser.model.DynamicObject
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/** Extracción de objetos desde líneas de log arbitrarias (datos sintéticos). */
class LogExtractorSpec extends Specification {

    @Unroll
    def "encuentra el objeto tras prefijos variados: '#prefijo'"() {
        when:
        def entradas = LogExtractor.extractAll(prefijo + 'Pedido(id=7, items=[A, B])')

        then:
        entradas.size() == 1
        def o = entradas[0].parsed() as DynamicObject
        o.className() == 'Pedido'
        o.properties() == [id: 7, items: ['A', 'B']]

        where:
        prefijo << [
                '',
                '[INFO] ',
                '2026-01-01 10:00:00,123 DEBUG [hilo-1] [abc] ',
                'c.b.s.MiClase: ',
                'com.empresa.app.Servicio$Interno - ',
                '>>> [{traza}] ',
        ]
    }

    @Unroll
    def "fragmento que empieza en '#inicio' con clases adentro: gana el contenedor externo"() {
        when:
        def v = LogExtractor.extractAll(texto)[0].parsed()

        then:
        tipo.isInstance(v)

        where:
        inicio | texto                                  || tipo
        '{'    | '{k=Foo(x=1), z=[1, 2]}'               || Map
        '['    | '[Foo(x=1), Foo(x=2)]'                 || List
        'Clase'| 'Envoltura(dato=Foo(x=1), n=2)'        || DynamicObject
    }

    def "varias líneas → varias entradas; líneas sin objeto se ignoran; CRLF y LF"() {
        given:
        def texto = 'arranque OK\r\nA(x=1)\n\nsolo texto\r\nB(y=[1])\n'

        when:
        def entradas = LogExtractor.extractAll(texto)

        then:
        entradas*.parsed()*.className() == ['A', 'B']
    }

    def "los offsets de los tramos son absolutos en el texto completo"() {
        given:
        def texto = 'linea previa\n   [WARN] x: Cliente(nombre=Ana, dir=Dir(calle=Sol, n=5))'

        when:
        def e = LogExtractor.extractAll(texto)[0]

        then:
        texto.substring(e.baseOffset()).startsWith('Cliente(')
        texto.substring(e.baseOffset(), e.finOffset()) == 'Cliente(nombre=Ana, dir=Dir(calle=Sol, n=5))'
        e.spans().every { sp ->
            def ultimo = sp.path().last()
            !(ultimo instanceof String) || texto.substring(sp.start()).startsWith(ultimo)
        }
    }

    def "rutaEn devuelve la propiedad más interna que contiene el offset"() {
        given:
        def texto = 'X(a=Y(b=Z(c=42)), d=1)'
        def e = LogExtractor.extractAll(texto)[0]

        expect:
        e.rutaEn(texto.indexOf('42')) == ['a', 'b', 'c']
        e.rutaEn(texto.indexOf('d=')) == ['d']
        e.contiene(texto.indexOf('42'))
    }

    @Timeout(10)
    def "rendimiento: una línea de ~250 000 caracteres se procesa rápido"() {
        given:
        def props = (1..9000).collect { "campo$it=valor$it" }.join(', ')
        def texto = '2026 INFO [x] Grande(' + props + ')'

        when:
        long t0 = System.nanoTime()
        def e = LogExtractor.extractAll(texto)[0]
        long ms = (System.nanoTime() - t0).intdiv(1_000_000)

        then:
        texto.length() > 150_000
        (e.parsed() as DynamicObject).properties().size() == 9000
        ms < 3000
    }
}
