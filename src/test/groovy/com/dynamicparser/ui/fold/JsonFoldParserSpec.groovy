package com.dynamicparser.ui.fold

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import spock.lang.Specification

/** Rangos plegables estilo Postman (las líneas de apertura y cierre quedan visibles). */
class JsonFoldParserSpec extends Specification {

    /** Rangos como "A-C" (1-based): abre en la línea A y cierra en la C. */
    private static List<String> rangos(String json) {
        def area = new RSyntaxTextArea(json)
        def folds = []
        def visitar
        visitar = { f ->
            folds << "${f.startLine + 1}-${f.endLine + 2}"
            (0..<f.childCount).each { visitar(f.getChild(it)) }
        }
        new JsonFoldParser().getFolds(area).each { visitar(it) }
        folds.sort()
    }

    def "rangos anidados; sólo plegables si ocultan al menos una línea"() {
        given:
        def json = '''{
    "a": {
        "b": 1
    },
    "c": [1, 2],
    "d": {
    }
}'''
        expect:
        rangos(json) == ['1-8', '2-4']        // "c" en una línea y "d" (sin líneas internas) no se pliegan
    }

    def "una línea con cierre y apertura ('}, \"x\": {') da dos rangos que no se pisan"() {
        given:
        def json = '''{
    "a": {
        "b": 1
    }, "x": {
        "y": 2
    }
}'''
        expect:
        rangos(json) == ['1-7', '2-4', '4-6']
    }

    def "llaves dentro de strings (y comillas escapadas) se ignoran"() {
        given:
        def json = '''{
    "s": "{ [ \\" } ]",
    "o": {
        "k": "}"
    }
}'''
        expect:
        rangos(json) == ['1-6', '3-5']
    }

    def "JSON desbalanceado (editado a mano) no falla: sólo pliega los pares que encajan"() {
        expect:
        rangos('''{
    "a": [
        1
    }
''') == []
    }
}
