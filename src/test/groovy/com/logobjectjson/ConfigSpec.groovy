package com.logobjectjson

import spock.lang.Specification

/** Configuration in application.yml (+ ~/.log-object-json, ./ and -Dlojson.*). */
class ConfigSpec extends Specification {

    def cleanup() {
        System.clearProperty('lojson.search.delayMs')
        System.clearProperty('lojson.new.key')
        Config.reload()
    }

    def "reads nested application.yml keys with dot notation"() {
        expect:
        Config.integer('search.delayMs') > 0
        Config.integer('sync.delayMs') > 0
        Config.string('json.extension').startsWith('.')
        !Config.string('json.defaultName').isBlank()
    }

    def "-Dlojson.<key> overrides the YAML"() {
        given:
        System.setProperty('lojson.search.delayMs', '987')
        System.setProperty('lojson.new.key', 'hello')

        when:
        Config.reload()

        then:
        Config.integer('search.delayMs') == 987
        Config.string('new.key') == 'hello'
    }

    def "a missing key fails with a clear message"() {
        when:
        Config.string('does.not.exist')

        then:
        def e = thrown(IllegalStateException)
        e.message.contains('does.not.exist')
    }

    def "every key the code reads exists in application.yml"() {
        given: 'keys read with Config.string/integer in the source code'
        def used = []
        new File('src/main/java').eachFileRecurse { f ->
            if (f.name.endsWith('.java')) {
                (f.text =~ /Config\.(?:string|integer)\("([^"]+)"\)/).each { used << it[1] }
            }
        }

        expect:
        !used.isEmpty()
        used.unique().every { key ->
            try { Config.string(key); true } catch (IllegalStateException ignored) { false }
        }
    }

    def "every message key the code uses exists in messages.properties"() {
        given: 'keys in Messages.get("key") and Messages.get(cond ? "a" : "b") calls'
        def used = []
        new File('src/main/java').eachFileRecurse { f ->
            if (f.name.endsWith('.java')) {
                (f.text =~ /Messages\.get\(\s*"([^"]+)"/).each { used << it[1] }
                (f.text =~ /Messages\.get\([^()"]*\?\s*"([^"]+)"\s*:\s*"([^"]+)"/).each { used << it[1] << it[2] }
            }
        }
        def bundle = ResourceBundle.getBundle('messages')

        expect:
        used.size() > 50
        used.unique().findAll { !bundle.containsKey(it) } == []
    }
}
