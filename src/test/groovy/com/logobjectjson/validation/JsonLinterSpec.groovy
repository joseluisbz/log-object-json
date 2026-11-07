package com.logobjectjson.validation

import com.logobjectjson.Messages
import com.logobjectjson.ui.JsonSerializer
import spock.lang.Specification
import spock.lang.Unroll

/** Tolerant JSON checker: reports every problem it can find, with its position. */
class JsonLinterSpec extends Specification {

    private static List<Problem> errors(String json) { JsonLinter.lint(json).findAll { it.isError() } }

    /** Text of the fragment each problem points to. */
    private static List<String> at(String json) { JsonLinter.lint(json).collect { json.substring(it.offset(), it.offset() + it.length()) } }

    @Unroll
    def "valid JSON has no problems: #json"() {
        expect:
        JsonLinter.lint(json).isEmpty()
        JsonSerializer.validate(json) == null

        where:
        json << ['{}', '[]', '{"a": 1, "b": [true, false, null], "c": {"d": "x\\"y"}}', '"text"', '-1.5e3',
                 '[{"a": []}, {}]']
    }

    def "several errors at once, each in its place"() {
        given:
        def json = '''{
    "a": 1,,
    "b": 2
    "c": True,
    d: 3,
}'''

        when:
        def ps = errors(json)

        then: 'extra comma, missing comma, invalid literal, unquoted key, trailing comma'
        ps*.message == [Messages.get('lint.extraComma'),
                        Messages.get('lint.missingComma', "'\"c\"'"),
                        Messages.get('lint.invalidValue', "'True'"),
                        Messages.get('lint.unquotedKey', "'d'"),
                        Messages.get('lint.trailingComma', "'}'")]
        at(json) == [',', '"c"', 'True', 'd', ',']
        ps*.offset == [json.indexOf(',,') + 1, json.indexOf('"c"'), json.indexOf('True'), json.indexOf('d:'), json.lastIndexOf(',')]
    }

    def "missing colon and missing value"() {
        expect:
        errors('{"a" 1, "b": }')*.message == [Messages.get('lint.missingColon', '"a"'), Messages.get('lint.missingValue', '"b"')]
    }

    def "unclosed string, single quotes and invalid numbers"() {
        given:
        def json = '{"a": "open,\n "b": \'x\', "c": 01, "d": 1.}'

        expect:
        errors(json)*.message.containsAll([Messages.get('lint.unterminatedString'),
                                           Messages.get('lint.singleQuotes', "'x'"),
                                           Messages.get('lint.invalidNumber', "'01'"),
                                           Messages.get('lint.invalidNumber', "'1.'")])
    }

    def "brackets: mismatched, unexpected and unclosed (pointing at the line where they opened)"() {
        expect:
        errors('{"a": [1, 2}')*.message == [Messages.get('lint.mismatchedClose', "']'", Messages.get('lint.array'), 1, "'}'")]
        errors('{"a": 1}}')*.message == [Messages.get('lint.afterEnd')]
        errors('[1, 2]]')*.message == [Messages.get('lint.afterEnd')]
        errors('{\n"a": {\n"b": 1\n')*.message == [Messages.get('lint.unclosed', Messages.get('lint.object'), 1, "'}'"),
                                                   Messages.get('lint.unclosed', Messages.get('lint.object'), 2, "'}'")]
    }

    def "a duplicate key is a warning (the JSON is still valid)"() {
        when:
        def ps = JsonLinter.lint('{"a": 1, "a": 2}')

        then:
        ps.size() == 1
        !ps[0].isError()
        ps[0].message == Messages.get('lint.duplicateKey', '"a"')
    }

    def "empty document"() {
        expect:
        errors('   ')*.message == [Messages.get('lint.empty')]
    }

    @Unroll
    def "never fails on arbitrary malformed text: '#text'"() {
        when:
        JsonLinter.lint(text)

        then:
        noExceptionThrown()

        where:
        text << ['{', '}', '[[[', ']]]', '{"a":', '{"a" "b" "c"}', ':::', ',,,', '"', '\'', '{"\\', '@#%', 'tru', '-']
    }

    def "random corruptions of a valid JSON: every invalid result has at least one error"() {
        given:
        def base = JsonSerializer.toPrettyJson(new com.logobjectjson.parser.ObjectParser(
                'P(id=1, lines=[L(prod=A, tags=[x, y]), L(prod=B)], extra={k=v, n=null}, ok=true)').parse())
        def rnd = new Random(7)

        expect:
        (1..200).every {
            def chars = base.toCharArray() as List
            int i = rnd.nextInt(chars.size())
            switch (rnd.nextInt(3)) {
                case 0: chars.remove(i); break
                case 1: chars.add(i, ",:{}[]\"x"[rnd.nextInt(8)] as char); break
                default: chars[i] = ",:{}[]\"x"[rnd.nextInt(8)] as char
            }
            def json = chars.join('')
            boolean invalid = JsonSerializer.validate(json) != null
            !invalid || !errors(json).isEmpty()
        }
    }
}
