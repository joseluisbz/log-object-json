package com.dynamicparser.parser

import com.dynamicparser.Config
import com.dynamicparser.model.DynamicObject
import com.dynamicparser.ui.JsonSerializer
import spock.lang.Specification
import spock.lang.TempDir
import spock.lang.Unroll

import java.nio.file.Path

/**
 * Dialectos configurables (dialectos.yml): Java, C#, Python y Rust.
 * Datos sintéticos; cada caso imita lo que imprime el lenguaje de forma estándar.
 */
class DialectosSpec extends Specification {

    @TempDir
    Path carpeta

    def cleanup() {
        System.clearProperty('parser.dialectos.archivo')
        Config.recargar()
        Dialecto.recargar()
    }

    private static Object parse(String texto, String dialecto) {
        new ObjectParser(texto, Dialecto.porNombre(dialecto)).parse()
    }

    private static LogExtractor.ParsedEntry extraer(String linea) {
        LogExtractor.extractAll(linea)[0]
    }

    /** Forma comparable: objetos → [@Clase: props]. */
    private static Object plano(Object v) {
        if (v instanceof DynamicObject) return ['@' + v.className(), plano(v.properties())]
        if (v instanceof Map) return v.collectEntries { k, x -> [(String.valueOf(k)): plano(x)] }
        if (v instanceof List) return v.collect { plano(it) }
        v
    }

    // ── Carga del archivo de dialectos ──────────────────────────────────────

    def "dialectos.yml (no application.yml) define java, csharp, python y rust; java es el de por defecto"() {
        expect:
        Config.texto('dialectos.archivo') == 'dialectos.yml'
        Dialecto.activos()*.nombre() == ['java', 'csharp', 'python', 'rust']
        Dialecto.porDefecto().nombre() == 'java'
        Dialecto.porNombre('python').nulos() == ['None'] as Set
    }

    def "un archivo propio reemplaza, agrega y desactiva dialectos por nombre"() {
        given:
        def propio = carpeta.resolve('mis-dialectos.yml')
        propio.toFile().text = '''
dialectos:
  - nombre: rust
    activo: false
  - nombre: python
    nulos: [ "None", "nada" ]
  - nombre: kotlin
    nulos: [ "null" ]
    verdaderos: [ "true" ]
    falsos: [ "false" ]
    separadores: "="
    comillas: "\\""
    objetoParentesis: true
'''
        System.setProperty('parser.dialectos.archivo', propio.toAbsolutePath().toString())

        when:
        Config.recargar()
        Dialecto.recargar()

        then:
        Dialecto.activos()*.nombre() == ['java', 'csharp', 'python', 'kotlin']
        Dialecto.porNombre('python').nulos() == ['None', 'nada'] as Set
        Dialecto.porNombre('python').comillas() == "'\""            // heredado del original
        !Dialecto.porNombre('rust').activo()
        (parse('P(x=nada)', 'python') as DynamicObject).properties() == [x: null]
    }

    // ── Python ──────────────────────────────────────────────────────────────

    def "Python: dataclass con comillas simples, None, True/False"() {
        expect:
        plano(parse("Persona(nombre='Ana', edad=3, activo=True, baja=False, extra=None)", 'python')) ==
                ['@Persona', [nombre: 'Ana', edad: 3, activo: true, baja: false, extra: null]]
    }

    def "Python: dict con ':' y claves entre comillas, lista, tupla y conjunto"() {
        expect:
        plano(parse("{'k': 1, \"j\": [1, 'x'], 't': (1, 2), 's': {3, 4}, 'v': {}}", 'python')) ==
                [k: 1, j: [1, 'x'], t: [1, 2], s: [3, 4], v: [:]]
    }

    def "Python: argumentos posicionales y nombres calificados (datetime.date(2026, 1, 1))"() {
        expect:
        plano(parse("Evento(fecha=datetime.date(2026, 1, 1), punto=Punto(1, 2))", 'python')) ==
                ['@Evento', [fecha: ['@datetime.date', ['0': 2026, '1': 1, '2': 1]],
                             punto: ['@Punto', ['0': 1, '1': 2]]]]
    }

    def "Python: comillas escapadas y comillas del otro tipo"() {
        expect:
        plano(parse("""P(a='it\\'s', b="dijo 'hola'", c='x,y)')""", 'python')) ==
                ['@P', [a: "it's", b: "dijo 'hola'", c: 'x,y)']]
    }

    // ── C# ──────────────────────────────────────────────────────────────────

    def "C#: record con llaves, anidado, True/False"() {
        expect:
        plano(parse('Persona { Nombre = Ana, Edad = 3, Activo = True, Dir = Direccion { Calle = Sol, Numero = 5 } }', 'csharp')) ==
                ['@Persona', [Nombre: 'Ana', Edad: 3, Activo: true,
                              Dir: ['@Direccion', [Calle: 'Sol', Numero: 5]]]]
    }

    def "C#: tipo anónimo { A = 1 } y ValueTuple (1, x)"() {
        expect:
        plano(parse('{ A = 1, B = texto libre, T = (1, x) }', 'csharp')) == [A: 1, B: 'texto libre', T: [1, 'x']]
    }

    // ── Rust ────────────────────────────────────────────────────────────────

    def "Rust: struct con ':' , None, Some(x), tuple struct, tupla, char y vector"() {
        expect:
        plano(parse('Persona { nombre: "Ana", edad: 3, activo: true, extra: None, v: Some(5), ' +
                "punto: Punto(1, 2), t: (1, \"x\"), c: 'z', tags: [\"a\", \"b\"] }", 'rust')) ==
                ['@Persona', [nombre: 'Ana', edad: 3, activo: true, extra: null, v: ['@Some', ['0': 5]],
                              punto: ['@Punto', ['0': 1, '1': 2]], t: [1, 'x'], c: 'z', tags: ['a', 'b']]]
    }

    def "Rust: variantes de enum con ruta (Color::Rojo) quedan como texto; struct con ruta como objeto"() {
        expect:
        plano(parse('Pedido { color: Color::Rojo, estado: estado::Estado { activo: true } }', 'rust')) ==
                ['@Pedido', [color: 'Color::Rojo', estado: ['@estado::Estado', [activo: true]]]]
    }

    // ── Detección automática del dialecto en líneas de log ─────────────────

    @Unroll
    def "detecta el dialecto #esperado en una línea de log con prefijo"() {
        when:
        def e = extraer('2026-01-01 12:00:00 INFO [hilo-1] Servicio: ' + objeto)

        then:
        e.dialecto() == esperado
        e.finOffset() > e.baseOffset()

        where:
        esperado | objeto
        'java'   | 'Persona(nombre=Ana, edad=3, extra=null, dir=Dir(calle=Sol))'
        'java'   | 'Punto[x=1, y=2]'
        'csharp' | 'Persona { Nombre = Ana, Edad = 3, Activo = True }'
        'python' | "Persona(nombre='Ana', edad=3, extra=None)"
        'python' | "{'k': 1, 'lista': [1, 2], 'nulo': None}"
        'rust'   | 'Persona { nombre: "Ana", edad: 3, extra: None }'
    }

    def "ambigüedad sin pistas (Foo(a=1)): gana el primero de la lista (java)"() {
        expect:
        extraer('Foo(a=1, b=x)').dialecto() == 'java'
    }

    def "los tramos (posiciones) funcionan en otros dialectos"() {
        given:
        def linea = "INFO P: Persona(nombre='Ana', dir=Dir(calle='Sol'))"
        def e = extraer(linea)

        expect:
        e.dialecto() == 'python'
        e.rutaEn(linea.indexOf('Sol')) == ['dir', 'calle']
        linea.substring(e.baseOffset(), e.finOffset()) == "Persona(nombre='Ana', dir=Dir(calle='Sol'))"
    }

    @Unroll
    def "lo parseado en #dialecto serializa a JSON válido"() {
        expect:
        JsonSerializer.validate(JsonSerializer.toPrettyJson(extraer(texto).parsed())) == null

        where:
        dialecto | texto
        'csharp' | 'Persona { Nombre = Ana, Tags = (1, 2), Dir = Dir { Calle = Sol } }'
        'python' | "P(a=None, b={'k': (1, 2)}, c={1, 2}, d=datetime.date(2026, 1, 1))"
        'rust'   | 'P { a: Some(Punto(1, 2)), b: None, c: [(1, "x")] }'
    }

    // ── Prueba aleatoria por dialecto ───────────────────────────────────────

    @Unroll
    def "aleatorio #dialecto (semilla #semilla): imprimir con su sintaxis → parsear → mismo valor"() {
        given:
        def rnd = new Random(semilla)
        def original = objeto(rnd, 0)
        def linea = '2026 INFO [x] ' + imprimir(original, dialecto)

        when:
        def e = extraer(linea)

        then:
        plano(e.parsed()) == plano(original)

        where:
        [dialecto, semilla] << [['csharp', 'python', 'rust'], (1..15)].combinations()
    }

    private static final List<String> CLASES = ['Alfa', 'BetaDto', 'Item']

    private static DynamicObject objeto(Random r, int nivel) {
        def props = new LinkedHashMap<String, Object>()
        (0..r.nextInt(3)).each { props['campo' + it] = valor(r, nivel + 1) }
        new DynamicObject(CLASES[r.nextInt(CLASES.size())], props)
    }

    private static Object valor(Random r, int nivel) {
        int t = nivel >= 3 ? r.nextInt(5) : r.nextInt(7)
        switch (t) {
            case 0: return r.nextInt(1000) - 500
            case 1: return r.nextBoolean()
            case 2: return null
            case 3: return 'texto' + r.nextInt(50)
            case 4: return Math.round(r.nextDouble() * 1000) / 10.0d
            case 5: return objeto(r, nivel)
            default: return (0..<r.nextInt(3)).collect { valor(r, nivel + 1) }
        }
    }

    /** Imprime como lo haría el lenguaje (record de C#, repr de Python, Debug de Rust). */
    private static String imprimir(Object v, String d) {
        if (v instanceof DynamicObject) {
            def props = v.properties().collect { k, x ->
                d == 'python' ? "$k=${imprimir(x, d)}" : d == 'csharp' ? "$k = ${imprimir(x, d)}" : "$k: ${imprimir(x, d)}"
            }.join(', ')
            return d == 'python' ? "${v.className()}($props)" : "${v.className()} { $props }"
        }
        if (v instanceof List) return '[' + v.collect { imprimir(it, d) }.join(', ') + ']'
        if (v == null) return d == 'csharp' ? 'null' : 'None'
        if (v instanceof Boolean) return d == 'rust' ? v.toString() : (v ? 'True' : 'False')
        if (v instanceof String) return d == 'csharp' ? v : (d == 'python' ? "'$v'" : "\"$v\"")
        String.valueOf(v)
    }
}
