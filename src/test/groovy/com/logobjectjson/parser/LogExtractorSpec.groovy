package com.logobjectjson.parser

import com.logobjectjson.model.DynamicObject
import spock.lang.Specification
import spock.lang.Timeout
import spock.lang.Unroll

/** Extracting objects from arbitrary log lines (synthetic data). */
class LogExtractorSpec extends Specification {

    @Unroll
    def "finds the object after varied prefixes: '#prefix'"() {
        when:
        def entries = LogExtractor.extractAll(prefix + 'Order(id=7, items=[A, B])')

        then:
        entries.size() == 1
        def o = entries[0].parsed() as DynamicObject
        o.className() == 'Order'
        o.properties() == [id: 7, items: ['A', 'B']]

        where:
        prefix << [
                '',
                '[INFO] ',
                '2026-01-01 10:00:00,123 DEBUG [thread-1] [abc] ',
                'c.b.s.MyClass: ',
                'com.company.app.Service$Inner - ',
                '>>> [{trace}] ',
        ]
    }

    @Unroll
    def "fragment starting with '#start' with classes inside: the outer container wins"() {
        when:
        def v = LogExtractor.extractAll(text)[0].parsed()

        then:
        type.isInstance(v)

        where:
        start  | text                                   || type
        '{'    | '{k=Foo(x=1), z=[1, 2]}'               || Map
        '['    | '[Foo(x=1), Foo(x=2)]'                 || List
        'Type' | 'Wrapper(data=Foo(x=1), n=2)'          || DynamicObject
    }

    def "several lines → several entries; lines without objects are ignored; CRLF and LF"() {
        given:
        def text = 'startup OK\r\nA(x=1)\n\njust text\r\nB(y=[1])\n'

        when:
        def entries = LogExtractor.extractAll(text)

        then:
        entries*.parsed()*.className() == ['A', 'B']
    }

    def "span offsets are absolute in the whole text"() {
        given:
        def text = 'previous line\n   [WARN] x: Customer(name=Ann, address=Address(street=Main, n=5))'

        when:
        def e = LogExtractor.extractAll(text)[0]

        then:
        text.substring(e.baseOffset()).startsWith('Customer(')
        text.substring(e.baseOffset(), e.endOffset()) == 'Customer(name=Ann, address=Address(street=Main, n=5))'
        e.spans().every { sp ->
            def last = sp.path().last()
            !(last instanceof String) || text.substring(sp.start()).startsWith(last)
        }
    }

    def "pathAt returns the innermost property containing the offset"() {
        given:
        def text = 'X(a=Y(b=Z(c=42)), d=1)'
        def e = LogExtractor.extractAll(text)[0]

        expect:
        e.pathAt(text.indexOf('42')) == ['a', 'b', 'c']
        e.pathAt(text.indexOf('d=')) == ['d']
        e.contains(text.indexOf('42'))
    }

    @Timeout(10)
    def "performance: a ~250,000 character line is processed fast"() {
        given:
        def props = (1..9000).collect { "field$it=value$it" }.join(', ')
        def text = '2026 INFO [x] Big(' + props + ')'

        when:
        long t0 = System.nanoTime()
        def e = LogExtractor.extractAll(text)[0]
        long ms = (System.nanoTime() - t0).intdiv(1_000_000)

        then:
        text.length() > 150_000
        (e.parsed() as DynamicObject).properties().size() == 9000
        ms < 3000
    }
}
