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

    private static List<String> messageKeysUsedInCode() {
        def used = []
        new File('src/main/java').eachFileRecurse { f ->
            if (f.name.endsWith('.java')) {
                (f.text =~ /Messages\.get\(\s*"([^"]+)"/).each { used << it[1] }
                (f.text =~ /Messages\.get\([^()"]*\?\s*"([^"]+)"\s*:\s*"([^"]+)"/).each { used << it[1] << it[2] }
                (f.text =~ /error\([^,]+,\s*"(lint\.[^"]+)"|"(lint\.[^"]+)"/).each { used << (it[1] ?: it[2]) }
            }
        }
        used.unique().findAll { !it.endsWith('.') }       // "prefix." + suffix keys are checked by the file test
    }

    def "every message key the code uses exists in every language"() {
        given:
        def used = messageKeysUsedInCode()

        expect:
        used.size() > 100
        Messages.languages() == ['en', 'es', 'fr', 'pt', 'de', 'it']
        Messages.languages().every { lang -> used.findAll { !Messages.has(lang, it) } == [] }
    }

    def "every language file has exactly the keys of the default language, with the same placeholders"() {
        given:
        def keys = { String lang ->
            def text = Messages.getResourceAsStream("/i18n/messages_${lang}.yml").getText('UTF-8')
            (text =~ /(?m)^"([^"]+)":/).collect { it[1] } as Set
        }
        def en = keys('en')

        expect:
        Messages.languages().every { keys(it) == en }
        Messages.languages().every { lang ->
            en.every { k -> placeholders(Messages.in(lang, k)) == placeholders(Messages.in('en', k)) }
        }
    }

    private static Set<String> placeholders(String s) { (s =~ /\{(\d)/).collect { it[1] } as Set }

    def "switching the language changes the texts and is remembered; unknown languages fall back"() {
        given:
        def previous = Messages.language()

        when:
        Messages.setLanguage('es')

        then:
        Messages.get('edit.copy') == 'Copiar'
        Messages.get('problems.position', 2189, 7) == 'Línea 2189, col 7'      // no thousands separator

        when:
        Messages.setLanguage('xx')

        then:
        Messages.language() == Messages.defaultLanguage()

        cleanup:
        Messages.setLanguage(previous)
    }

    def "apostrophes in translations are literal (no MessageFormat quoting surprises)"() {
        given:
        def previous = Messages.language()
        Messages.setLanguage('fr')

        expect:
        Messages.get('status.fileLoaded', 'x.log', 3).contains('chargé : x.log')
        Messages.get('lint.unclosed', 'objet', 12, "'}'").contains("'}'")

        cleanup:
        Messages.setLanguage(previous)
    }
}
