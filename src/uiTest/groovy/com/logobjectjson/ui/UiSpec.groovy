package com.logobjectjson.ui

import spock.lang.Specification
import spock.util.concurrent.PollingConditions

import javax.swing.*
import javax.swing.tree.DefaultMutableTreeNode
import java.awt.*
import java.awt.event.InputEvent

/**
 * Base for the UI tests: opens a real window, gives access to the session/Root
 * tab and utilities (EDT, waits, real clicks with Robot, automatic closing of
 * modal dialogs).
 *
 * Groovy allows access to private members: they are used to observe internal
 * state without adding test-only API.
 */
abstract class UiSpec extends Specification {

    MainFrame frame
    PollingConditions eventually = new PollingConditions(timeout: 15, delay: 0.2)

    def setup() {
        onEdt {
            UIManager.setLookAndFeel(UIManager.systemLookAndFeelClassName)
            frame = new MainFrame()
            frame.setSize(1300, 850)
            frame.setLocation(40, 40)
            frame.visible = true
            frame.alwaysOnTop = true     // Windows does not let a window launched from Gradle come to the front
            frame.toFront()
        }
    }

    def cleanup() {
        onEdt { frame?.dispose() }
    }

    // ── Utilities ───────────────────────────────────────────────────────────

    static <T> T onEdt(Closure<T> c) {
        if (SwingUtilities.isEventDispatchThread()) return c()      // reentrant
        T r = null
        Throwable err = null
        SwingUtilities.invokeAndWait { try { r = c() } catch (Throwable t) { err = t } }
        if (err) throw err
        r
    }

    JTabbedPane sessions() { frame.contentPane as JTabbedPane }

    SessionPanel session(int i = 0) { onEdt { sessions().getComponentAt(i) as SessionPanel } }

    TrioPanel main(SessionPanel s = session()) { s.@main }

    JTabbedPane trios(SessionPanel s = session()) { s.@trios }

    static String text(TrioPanel t) { onEdt { t.@textArea.text } }

    static void setText(TrioPanel t, String s) { onEdt { t.@textArea.text = s } }

    static String json(TrioPanel t) { onEdt { t.@jsonPanel?.json } }

    static String sync(TrioPanel t) { onEdt { t.@syncText } }

    static JTree tree(TrioPanel t) { t.@resultTree }

    /** Title shown on the tab of session 0. */
    String sessionTitle() {
        onEdt { (sessions().getTabComponentAt(0) as JPanel).components.find { it instanceof JLabel }.text }
    }

    /** First tree node whose label starts with the prefix. */
    static DefaultMutableTreeNode node(TrioPanel t, String prefix) {
        onEdt {
            def root = tree(t).model.root as DefaultMutableTreeNode
            root.preorderEnumeration().find { it.toString().startsWith(prefix) } as DefaultMutableTreeNode
        }
    }

    /** Waits until the tab is in sync with its text. */
    void waitInSync(TrioPanel t) {
        eventually.eventually {
            assert sync(t).startsWith('✔')
            assert onEdt { t.@textArea.text == t.@instantiatedText }
        }
    }

    /** Real left click (Robot) on tab idx of the JTabbedPane. */
    static void clickTab(JTabbedPane tabs, int idx) {
        Point p = onEdt {
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
     * Runs the action, automatically closing any modal dialog it opens
     * (returns the messages of the dialogs that were closed).
     */
    static java.util.List<String> closingDialogs(Closure action) {
        def messages = Collections.synchronizedList([])
        def timer = new javax.swing.Timer(200, {
            Window.windows.findAll { it instanceof JDialog && it.visible }.each { JDialog d ->
                messages << textsOf(d).join(' ')
                d.dispose()
            }
        })
        timer.start()
        try {
            SwingUtilities.invokeAndWait { action() }
        } finally {
            timer.stop()
        }
        messages
    }

    private static java.util.List<String> textsOf(Container c) {
        def out = []
        c.components.each { k ->
            if (k instanceof JLabel && k.text) out << k.text
            if (k instanceof JTextArea) out << k.text
            if (k instanceof Container) out.addAll(textsOf(k))
        }
        out
    }
}
