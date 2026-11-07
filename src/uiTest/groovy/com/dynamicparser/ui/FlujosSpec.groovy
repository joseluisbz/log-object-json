package com.dynamicparser.ui

import com.fasterxml.jackson.databind.ObjectMapper
import spock.lang.Stepwise
import spock.lang.TempDir

import javax.swing.*
import javax.swing.tree.TreePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * Pruebas funcionales de la interfaz (ventana real). Datos sintéticos, no
 * acoplados a ningún log concreto.
 */
class FlujosSpec extends UiSpec {

    static final String LOG = '2026-01-01 INFO [hilo-1] Servicio: ' +
            'Pedido(id=7, cliente=Ana, fecha=Fri Nov 10 21:00:00 CLST 2000, ' +
            'lineas=[Linea(prod=A, cant=2), Linea(prod=B-22/x, cant=1)], extra={k=Foo(x=1), z=[1, 2]})'

    static final ObjectMapper JSON = new ObjectMapper()

    @TempDir
    Path carpeta

    def "pegar texto: se instancia y serializa solo; título 'Texto pegado'"() {
        when:
        ponerTexto(principal(), LOG)

        then:
        esperarSincronizado(principal())
        nodo(principal(), 'Pedido') != null
        JSON.readTree(json(principal())).path('cliente').asText() == 'Ana'
        enEdt { (sesiones().getTabComponentAt(0) as JPanel).components.find { it instanceof JLabel }.text } == 'Texto pegado'
    }

    def "abrir archivo: título = nombre del archivo; árbol y JSON listos"() {
        given:
        def archivo = carpeta.resolve('ejemplo.log')
        Files.writeString(archivo, LOG)

        when:
        enEdt { principal().cargarArchivo(archivo) }

        then:
        esperarSincronizado(principal())
        enEdt { (sesiones().getTabComponentAt(0) as JPanel).components.find { it instanceof JLabel }.text } == 'ejemplo.log'
        json(principal()) != null
    }

    def "editar un valor en el texto actualiza árbol y JSON, conservando la selección"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        enEdt {
            def n = nodo(principal(), 'cant  =  2')
            arbol(principal()).selectionPath = new TreePath(n.path)
        }

        when:
        enEdt {
            def area = principal().@textArea
            int i = area.text.indexOf('cant=2')
            area.select(i + 5, i + 6)
            area.replaceSelection('9')
        }

        then:
        eventualmente.eventually {
            assert sync(principal()).startsWith('✔')
            assert JSON.readTree(json(principal())).path('lineas').get(0).path('cant').asInt() == 9
            assert enEdt { arbol(principal()).selectionPath?.lastPathComponent?.toString() } == 'cant  =  9'
        }
    }

    def "texto roto: se avisa y se conserva la última versión válida"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        def jsonAntes = json(principal())

        when:
        ponerTexto(principal(), 'sin objetos aquí')

        then:
        eventualmente.eventually { assert sync(principal()).startsWith('⚠') }
        json(principal()) == jsonAntes
        nodo(principal(), 'Pedido') != null
    }

    def "un JSON editado a mano no se sobrescribe al cambiar el texto"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        enEdt {
            def editor = principal().@jsonPanel.@editor
            editor.editable = true
            editor.insert(' ', 0)
        }
        def jsonEditado = json(principal())

        when:
        enEdt { principal().@textArea.insert(' ', 0) }

        then:
        eventualmente.eventually { assert sync(principal()).startsWith('✔') }
        json(principal()) == jsonEditado
        enEdt { principal().@jsonPanel.@lblInfo.text }.contains('Editado a mano')
    }

    def "texto → árbol: ubica la propiedad bajo el cursor (instanciando si hace falta)"() {
        given:
        ponerTexto(principal(), LOG)
        int off = LOG.indexOf('prod=B') + 1

        when:
        enEdt { principal().ubicarDesdeFuente(off) }

        then:
        eventualmente.eventually {
            assert enEdt { arbol(principal()).selectionPath?.lastPathComponent?.toString() } == 'prod  =  "B-22/x"'
        }
    }

    def "JSON → árbol y texto a la vez, sin ciclos"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        def origen = enEdt { principal().@jsonPanel.getClientProperty('json.origen') }

        when:
        enEdt {
            principal().ubicarEnArbol(origen, ['extra', 'k', 'x'])
            principal().ubicarEnFuente(origen, ['extra', 'k', 'x'])
        }

        then:
        enEdt { arbol(principal()).selectionPath.lastPathComponent.toString() } == 'x  =  1'
        enEdt { principal().@textArea.selectedText } == 'x'
        sync(principal()).startsWith('✔')            // no se disparó otra sincronización
    }

    def "analizar un nodo abre una pestaña con su texto, su árbol y su JSON"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        def jsonPrincipal = JSON.readTree(json(principal()))

        when:
        enEdt { principal().extraerRama(nodo(principal(), 'extra')) }
        def rama = enEdt { trios().getComponentAt(1) as TrioPanel }

        then:
        enEdt { trios().tabCount } == 2
        esperarSincronizado(rama)
        texto(rama) == '{k=Foo(x=1), z=[1, 2]}'
        JSON.readTree(json(rama)) == jsonPrincipal.path('extra')
    }

    def "se puede ir y volver entre pestañas con clics reales; la rama se cierra y la Principal no"() {
        given:
        ponerTexto(principal(), LOG)
        esperarSincronizado(principal())
        enEdt { principal().extraerRama(nodo(principal(), 'lineas')) }

        when:
        def seleccion = []
        [0, 1, 0, 1].each { i -> clicEnPestana(trios(), i); seleccion << enEdt { trios().selectedIndex } }

        then:
        seleccion == [0, 1, 0, 1]

        when: 'cerrar la rama con su ✕'
        enEdt {
            def cab = trios().getTabComponentAt(1) as JPanel
            (cab.components.find { it instanceof JButton } as JButton).doClick()
        }

        then:
        enEdt { trios().tabCount } == 1
        enEdt { trios().getTabComponentAt(0) } == null       // la Principal no tiene ✕
    }

    def "sesiones independientes; cerrar la última deja una nueva vacía"() {
        when:
        def s2 = enEdt { frame.nuevaSesion() }
        ponerTexto(principal(s2), 'Otro(a=1)')
        esperarSincronizado(principal(s2))

        then:
        texto(principal()) == ''
        enEdt { sesiones().tabCount } == 3                     // 2 sesiones + "+"

        when:
        enEdt { frame.cerrarSesion(sesion(0)); frame.cerrarSesion(sesion(0)) }

        then:
        enEdt { sesiones().tabCount } == 2                     // 1 sesión nueva + "+"
        texto(principal()) == ''
    }

    def "reemplazar todo con regex y deshacer con un solo Ctrl+Z"() {
        given:
        ponerTexto(principal(), 'A(v1=10, v2=20, v3=30)')
        esperarSincronizado(principal())
        def br = principal().@buscarFuente

        when:
        enEdt {
            br.@tglRegex.selected = true
            br.@txtBuscar.text = /v(\d)=(\d+)/
            br.@txtReemplazar.text = 'w$1=$2'
            br.reemplazarTodo()
        }

        then:
        texto(principal()) == 'A(w1=10, w2=20, w3=30)'

        when:
        enEdt { principal().@textArea.undoLastAction() }

        then:
        texto(principal()) == 'A(v1=10, v2=20, v3=30)'
    }

    def "guardar propone la carpeta del último .log abierto, desde otra sesión"() {
        given:
        def prefs = Preferences.userNodeForPackage(TrioPanel)
        def previo = prefs.get('ultimoArchivoLog', null)
        prefs.put('ultimoArchivoLog', carpeta.resolve('algo.log').toString())

        when:
        def s2 = enEdt { frame.nuevaSesion() }
        ponerTexto(principal(s2), 'Otro(a=1)')
        def propuesta = enEdt { principal(s2).propuestaJson() } as File

        then:
        propuesta.parentFile.toPath() == carpeta
        propuesta.name == com.dynamicparser.Config.texto('json.nombrePorDefecto')

        cleanup:
        previo != null ? prefs.put('ultimoArchivoLog', previo) : prefs.remove('ultimoArchivoLog')
    }

    def "Wrap se rechaza con una línea gigante (sin congelar la interfaz)"() {
        given:
        int largo = com.dynamicparser.Config.entero('editor.maxLineaAjuste') + 1000
        ponerTexto(principal(), 'G(a=' + ('x' * largo) + ')')

        when:
        def dialogos = cerrandoDialogos {
            principal().@tglAjustar.selected = true
            principal().cambiarAjuste()
        }

        then:
        !enEdt { principal().@textArea.lineWrap }
        dialogos.any { it.contains('caracteres') }
    }

    def "estrés: con una línea de ~250 000 caracteres la interfaz sigue respondiendo"() {
        given:
        def grande = 'X INFO [t] Grande(' + (1..9000).collect { "campo$it=valor$it" }.join(', ') + ')'

        when:
        ponerTexto(principal(), grande)
        esperarSincronizado(principal())
        long t0 = System.nanoTime()
        enEdt { principal().@buscarFuente.@txtBuscar.text = 'campo8999' }
        Thread.sleep(com.dynamicparser.Config.entero('busqueda.esperaMs') + 200)
        long ida = System.nanoTime()
        enEdt { null }                                       // ida y vuelta por la EDT
        long latenciaMs = (System.nanoTime() - ida).intdiv(1_000_000)

        then:
        latenciaMs < 1000
        enEdt { principal().@textArea.selectedText } == 'campo8999'
    }
}
