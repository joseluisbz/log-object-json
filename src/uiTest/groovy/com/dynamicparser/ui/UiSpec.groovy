package com.dynamicparser.ui

import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.*
import java.awt.event.InputEvent

/**
 * Base de las pruebas de interfaz: abre una ventana real, ofrece acceso a la
 * sesión/pestaña principal y utilidades (EDT, esperas, clics reales con Robot,
 * cierre automático de diálogos modales).
 *
 * Groovy permite acceder a miembros privados: se usan para observar el estado
 * interno sin agregar API sólo para las pruebas.
 */
abstract class UiSpec extends Specification {

    MainFrame frame
    PollingConditions eventualmente = new PollingConditions(timeout: 15, delay: 0.2)

    def setup() {
        enEdt {
            UIManager.setLookAndFeel(UIManager.systemLookAndFeelClassName)
            frame = new MainFrame()
            frame.setSize(1300, 850)
            frame.setLocation(40, 40)
            frame.visible = true
            frame.alwaysOnTop = true     // Windows impide traer al frente una ventana lanzada desde Gradle
            frame.toFront()
        }
    }

    def cleanup() {
        enEdt { frame?.dispose() }
    }

    // ── Utilidades ──────────────────────────────────────────────────────────

    static <T> T enEdt(Closure<T> c) {
        if (SwingUtilities.isEventDispatchThread()) return c()      // reentrante
        T r = null
        Throwable err = null
        SwingUtilities.invokeAndWait { try { r = c() } catch (Throwable t) { err = t } }
        if (err) throw err
        r
    }

    JTabbedPane sesiones() { frame.contentPane as JTabbedPane }

    SesionPanel sesion(int i = 0) { enEdt { sesiones().getComponentAt(i) as SesionPanel } }

    TrioPanel principal(SesionPanel s = sesion()) { s.@principal }

    JTabbedPane trios(SesionPanel s = sesion()) { s.@trios }

    static String texto(TrioPanel t) { enEdt { t.@textArea.text } }

    static void ponerTexto(TrioPanel t, String s) { enEdt { t.@textArea.text = s } }

    static String json(TrioPanel t) { enEdt { t.@jsonPanel?.json } }

    static String sync(TrioPanel t) { enEdt { t.@lblSync.text } }

    static JTree arbol(TrioPanel t) { t.@arbolResultado }

    /** Primer nodo del árbol cuya etiqueta empieza con el prefijo. */
    static DefaultMutableTreeNode nodo(TrioPanel t, String prefijo) {
        enEdt {
            def raiz = arbol(t).model.root as DefaultMutableTreeNode
            raiz.preorderEnumeration().find { it.toString().startsWith(prefijo) } as DefaultMutableTreeNode
        }
    }

    /** Espera a que la pestaña esté sincronizada con su texto. */
    void esperarSincronizado(TrioPanel t) {
        eventualmente.eventually {
            assert sync(t).startsWith('✔')
            assert enEdt { t.@textArea.text == t.@textoInstanciado }
        }
    }

    /** Clic izquierdo real (Robot) en el centro de la pestaña idx del JTabbedPane. */
    static void clicEnPestana(JTabbedPane tabs, int idx) {
        Point p = enEdt {
            def r = tabs.getBoundsAt(idx)
            def q = new Point(r.x + (r.width / 3) as int, r.y + (r.height / 2) as int)
            SwingUtilities.convertPointToScreen(q, tabs)
            q
        }
        def robot = new Robot()
        robot.mouseMove(p.x as int, p.y as int)
        robot.mousePress(InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()
        Thread.sleep(300)
    }

    /**
     * Ejecuta la acción cerrando automáticamente cualquier diálogo modal que abra
     * (devuelve los mensajes de los diálogos que se cerraron).
     */
    static java.util.List<String> cerrandoDialogos(Closure accion) {
        def mensajes = Collections.synchronizedList([])
        def timer = new javax.swing.Timer(200, {
            Window.windows.findAll { it instanceof JDialog && it.visible }.each { JDialog d ->
                mensajes << textosDe(d).join(' ')
                d.dispose()
            }
        })
        timer.start()
        try {
            SwingUtilities.invokeAndWait { accion() }
        } finally {
            timer.stop()
        }
        mensajes
    }

    private static java.util.List<String> textosDe(Container c) {
        def out = []
        c.components.each { k ->
            if (k instanceof JLabel && k.text) out << k.text
            if (k instanceof JTextArea) out << k.text
            if (k instanceof Container) out.addAll(textosDe(k))
        }
        out
    }
}
