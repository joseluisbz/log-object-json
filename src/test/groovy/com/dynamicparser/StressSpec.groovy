package com.dynamicparser

import com.dynamicparser.model.DynamicObject
import com.dynamicparser.parser.LogExtractor
import com.dynamicparser.parser.ObjectParser
import com.dynamicparser.ui.JsonSerializer
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Pruebas de estrés (sin interfaz): volumen, anchura, profundidad y memoria.
 * Los umbrales son holgados: detectan degradaciones graves (p. ej. O(n²)), no microsegundos.
 */
class StressSpec extends Specification {

    private static long ms(Closure c) {
        long t0 = System.nanoTime(); c(); (System.nanoTime() - t0).intdiv(1_000_000)
    }

    @Timeout(30)
    def "muchas líneas: #lineas entradas con prefijo de log"() {
        given:
        def texto = (1..lineas).collect { "2026-01-01 INFO [h-$it] Evento(id=$it, datos={a=$it, b=[x, y]})" }.join('\n')

        when:
        List entradas
        long t = ms { entradas = LogExtractor.extractAll(texto) }

        then:
        entradas.size() == lineas
        (entradas.last().parsed() as DynamicObject).properties().id == lineas
        t < limiteMs

        where:
        lineas | limiteMs
        1_000  | 3_000
        20_000 | 15_000
    }

    @Timeout(30)
    def "objeto muy ancho: #props propiedades en una sola línea"() {
        given:
        def texto = 'Ancho(' + (1..props).collect { "p$it=v$it" }.join(', ') + ')'

        when:
        Object o
        long t = ms { o = new ObjectParser(texto).parse() }

        then:
        (o as DynamicObject).properties().size() == props
        t < 5_000

        where:
        props << [10_000, 100_000]
    }

    @Timeout(30)
    def "lista muy larga: 200 000 elementos"() {
        given:
        def texto = '[' + (1..200_000).join(', ') + ']'

        expect:
        (new ObjectParser(texto).parse() as List).size() == 200_000
    }

    @Timeout(30)
    def "anidamiento profundo: #niveles niveles (recursión, hilo con pila grande)"() {
        given:
        def texto = ('A(b=' * niveles) + '1' + (')' * niveles)

        when:
        def o = LogExtractor.extractAll(texto)[0].parsed()
        int prof = 0
        while (o instanceof DynamicObject) { o = o.properties().b; prof++ }

        then:
        prof == niveles

        where:
        niveles << [500, 5_000, 20_000]
    }

    @Timeout(60)
    def "archivo de ~10 MB: parsea y serializa sin agotar memoria"() {
        given:
        def linea = 'X INFO [t] Doc(' + (1..40).collect { "campo$it=valor-$it-abcdefghij" }.join(', ') + ')'
        int n = 10_000_000.intdiv(linea.length()) + 1
        def texto = ([linea] * n).join('\n')
        def rt = Runtime.runtime
        rt.gc()
        long antes = rt.totalMemory() - rt.freeMemory()

        when:
        def entradas = LogExtractor.extractAll(texto)
        def json = JsonSerializer.toPrettyJson(entradas*.parsed())
        long despues = rt.totalMemory() - rt.freeMemory()
        println "10 MB: ${entradas.size()} entradas, JSON ${json.length() >> 20} MB, memoria usada ≈ ${(despues - antes) >> 20} MB de ${rt.maxMemory() >> 20} MB"

        then:
        texto.length() >= 10_000_000
        entradas.size() == n
        JsonSerializer.validate(json) == null
        (despues - antes) < rt.maxMemory()        // no se acerca al límite del heap
    }

    @Timeout(20)
    def "texto mal formado y gigante no cuelga (bucles con guarda de progreso)"() {
        given:
        def texto = ('{a=[1, 2}' * 20_000) + ('Foo(x=' * 2_000) + ('}])' * 5_000)

        when:
        def entradas = LogExtractor.extractAll(texto)

        then: 'termina (sin bucles ni desbordes); el resultado puede ser parcial'
        entradas != null
    }
}
