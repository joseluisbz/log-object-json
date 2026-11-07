package com.logobjectjson

import com.logobjectjson.model.DynamicObject
import com.logobjectjson.parser.LogExtractor
import com.logobjectjson.parser.ObjectParser
import com.logobjectjson.ui.JsonSerializer
import spock.lang.Specification
import spock.lang.Timeout

/**
 * Stress tests (headless): volume, width, depth and memory.
 * Thresholds are loose: they catch severe regressions (e.g. O(n²)), not microseconds.
 */
class StressSpec extends Specification {

    private static long ms(Closure c) {
        long t0 = System.nanoTime(); c(); (System.nanoTime() - t0).intdiv(1_000_000)
    }

    @Timeout(30)
    def "many lines: #lines entries with a log prefix"() {
        given:
        def text = (1..lines).collect { "2026-01-01 INFO [t-$it] Event(id=$it, data={a=$it, b=[x, y]})" }.join('\n')

        when:
        List entries
        long t = ms { entries = LogExtractor.extractAll(text) }

        then:
        entries.size() == lines
        (entries.last().parsed() as DynamicObject).properties().id == lines
        t < limitMs

        where:
        lines  | limitMs
        1_000  | 3_000
        20_000 | 15_000
    }

    @Timeout(30)
    def "very wide object: #props properties on a single line"() {
        given:
        def text = 'Wide(' + (1..props).collect { "p$it=v$it" }.join(', ') + ')'

        when:
        Object o
        long t = ms { o = new ObjectParser(text).parse() }

        then:
        (o as DynamicObject).properties().size() == props
        t < 5_000

        where:
        props << [10_000, 100_000]
    }

    @Timeout(30)
    def "very long list: 200,000 elements"() {
        given:
        def text = '[' + (1..200_000).join(', ') + ']'

        expect:
        (new ObjectParser(text).parse() as List).size() == 200_000
    }

    @Timeout(30)
    def "deep nesting: #levels levels (recursion, large-stack thread)"() {
        given:
        def text = ('A(b=' * levels) + '1' + (')' * levels)

        when:
        def o = LogExtractor.extractAll(text)[0].parsed()
        int depth = 0
        while (o instanceof DynamicObject) { o = o.properties().b; depth++ }

        then:
        depth == levels

        where:
        levels << [500, 5_000, 20_000]
    }

    @Timeout(60)
    def "~10 MB file: parses and serializes without running out of memory"() {
        given:
        def line = 'X INFO [t] Doc(' + (1..40).collect { "field$it=value-$it-abcdefghij" }.join(', ') + ')'
        int n = 10_000_000.intdiv(line.length()) + 1
        def text = ([line] * n).join('\n')
        def rt = Runtime.runtime
        rt.gc()
        long before = rt.totalMemory() - rt.freeMemory()

        when:
        def entries = LogExtractor.extractAll(text)
        def json = JsonSerializer.toPrettyJson(entries*.parsed())
        long after = rt.totalMemory() - rt.freeMemory()
        println "10 MB: ${entries.size()} entries, JSON ${json.length() >> 20} MB, memory used ≈ ${(after - before) >> 20} MB of ${rt.maxMemory() >> 20} MB"

        then:
        text.length() >= 10_000_000
        entries.size() == n
        JsonSerializer.validate(json) == null
        (after - before) < rt.maxMemory()        // does not get close to the heap limit
    }

    @Timeout(20)
    def "huge malformed text does not hang (loops with a progress guard)"() {
        given:
        def text = ('{a=[1, 2}' * 20_000) + ('Foo(x=' * 2_000) + ('}])' * 5_000)

        when:
        def entries = LogExtractor.extractAll(text)

        then: 'it finishes (no loops or overflows); the result may be partial'
        entries != null
    }
}
