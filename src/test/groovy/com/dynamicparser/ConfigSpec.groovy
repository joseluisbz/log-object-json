package com.dynamicparser

import spock.lang.Specification

/** Configuración en application.yml (+ ~/.parser-dinamico, ./ y -Dparser.*). */
class ConfigSpec extends Specification {

    def cleanup() {
        System.clearProperty('parser.busqueda.esperaMs')
        System.clearProperty('parser.nueva.clave')
        Config.recargar()
    }

    def "lee claves anidadas del application.yml con notación de puntos"() {
        expect:
        Config.entero('busqueda.esperaMs') > 0
        Config.entero('sincronizacion.esperaMs') > 0
        Config.texto('json.extension').startsWith('.')
        !Config.texto('json.nombrePorDefecto').isBlank()
    }

    def "-Dparser.<clave> sobrescribe el YAML"() {
        given:
        System.setProperty('parser.busqueda.esperaMs', '987')
        System.setProperty('parser.nueva.clave', 'hola')

        when:
        Config.recargar()

        then:
        Config.entero('busqueda.esperaMs') == 987
        Config.texto('nueva.clave') == 'hola'
    }

    def "una clave inexistente falla con un mensaje claro"() {
        when:
        Config.texto('no.existe')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('no.existe')
    }

    def "todas las claves que usa el código existen en application.yml"() {
        given: 'claves leídas con Config.texto/entero en el código fuente'
        def usadas = new File('src/main/java').traverse(nameFilter: ~/.*\.java/).collect { null }
        usadas = []
        new File('src/main/java').eachFileRecurse { f ->
            if (f.name.endsWith('.java')) {
                (f.text =~ /Config\.(?:texto|entero)\("([^"]+)"\)/).each { usadas << it[1] }
            }
        }

        expect:
        !usadas.isEmpty()
        usadas.unique().every { clave ->
            try { Config.texto(clave); true } catch (IllegalStateException ignored) { false }
        }
    }
}
