package com.logobjectjson.ui.fold

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea
import spock.lang.Specification

/** Postman-style foldable ranges (the opening and closing lines stay visible). */
class JsonFoldParserSpec extends Specification {

    /** Ranges as "A-C" (1-based): opens on line A and closes on line C. */
    private static List<String> ranges(String json) {
        def area = new RSyntaxTextArea(json)
        def folds = []
        def visit
        visit = { f ->
            folds << "${f.startLine + 1}-${f.endLine + 2}"
            (0..<f.childCount).each { visit(f.getChild(it)) }
        }
        new JsonFoldParser().getFolds(area).each { visit(it) }
        folds.sort()
    }

    def "nested ranges; foldable only if they hide at least one line"() {
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
        ranges(json) == ['1-8', '2-4']        // "c" on one line and "d" (no inner lines) do not fold
    }

    def "a line with a closing and an opening ('}, \"x\": {') gives two non-overlapping ranges"() {
        given:
        def json = '''{
    "a": {
        "b": 1
    }, "x": {
        "y": 2
    }
}'''
        expect:
        ranges(json) == ['1-7', '2-4', '4-6']
    }

    def "brackets inside strings (and escaped quotes) are ignored"() {
        given:
        def json = '''{
    "s": "{ [ \\" } ]",
    "o": {
        "k": "}"
    }
}'''
        expect:
        ranges(json) == ['1-6', '3-5']
    }

    def "unbalanced JSON (hand edited) does not fail: only matching pairs fold"() {
        expect:
        ranges('''{
    "a": [
        1
    }
''') == []
    }
}
