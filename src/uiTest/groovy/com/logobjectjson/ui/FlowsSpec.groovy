package com.logobjectjson.ui

import com.fasterxml.jackson.databind.ObjectMapper
import com.logobjectjson.Config
import com.logobjectjson.Messages
import spock.lang.TempDir

import javax.swing.*
import java.awt.Point
import java.awt.Robot
import java.awt.Container
import javax.swing.tree.TreePath
import java.nio.file.Files
import java.nio.file.Path
import java.util.prefs.Preferences

/**
 * Functional UI tests (real window). Synthetic data, not coupled to any
 * particular log.
 */
class FlowsSpec extends UiSpec {

    static final String LOG = '2026-01-01 INFO [thread-1] Service: ' +
            'Order(id=7, customer=Ann, date=Fri Nov 10 21:00:00 CLST 2000, ' +
            'lines=[Line(prod=A, qty=2), Line(prod=B-22/x, qty=1)], extra={k=Foo(x=1), z=[1, 2]})'

    static final ObjectMapper JSON = new ObjectMapper()

    @TempDir
    Path folder

    def "pasting text: instantiates and serializes by itself; title 'Pasted text'"() {
        when:
        setText(main(), LOG)

        then:
        waitInSync(main())
        node(main(), 'Order') != null
        JSON.readTree(json(main())).path('customer').asText() == 'Ann'
        sessionTitle() == Messages.get('trio.pastedText')
    }

    def "the recognized languages are shown next to the sync status; tree and JSON toolbars are aligned"() {
        when:
        setText(main(), LOG)

        then:
        waitInSync(main())
        onEdt { main().@lblLanguage.visible && main().@lblLanguage.text == 'Java · Kotlin' }
        onEdt {
            def treeBar = findAll(main(), JToolBar).find { !findAll(it, TreeSearchBar).isEmpty() }
            def jsonBars = main().@jsonPanel.@bars
            treeBar.height == jsonBars.height
        }

        when: 'the text stops being a valid object'
        setText(main(), 'no objects here')

        then:
        eventually.eventually { assert sync(main()).startsWith('⚠') }
        !onEdt { main().@lblLanguage.visible }
    }

    def "opening a file: title = file name; tree and JSON ready"() {
        given:
        def file = folder.resolve('sample.log')
        Files.writeString(file, LOG)

        when:
        onEdt { main().loadFile(file) }

        then:
        waitInSync(main())
        sessionTitle() == 'sample.log'
        json(main()) != null
    }

    def "several independent objects are an error: no tree and no JSON"() {
        when:
        setText(main(), 'A(x=1)\nnoise\nB(y=2)')

        then:
        eventually.eventually { assert sync(main()) == Messages.get('sync.severalObjects') }
        json(main()) == null
        node(main(), 'A') == null
        onEdt { main().@lblStatus.text }.contains('1, 3')
    }

    def "editing a value in the text updates tree and JSON, keeping the selection"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        onEdt {
            def n = node(main(), 'qty  =  2')
            tree(main()).selectionPath = new TreePath(n.path)
        }

        when:
        onEdt {
            def area = main().@textArea
            int i = area.text.indexOf('qty=2')
            area.select(i + 4, i + 5)
            area.replaceSelection('9')
        }

        then:
        eventually.eventually {
            assert sync(main()).startsWith('✔')
            assert JSON.readTree(json(main())).path('lines').get(0).path('qty').asInt() == 9
            assert onEdt { tree(main()).selectionPath?.lastPathComponent?.toString() } == 'qty  =  9'
        }
    }

    def "broken text: a warning is shown and the last valid version is kept"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def jsonBefore = json(main())

        when:
        setText(main(), 'no objects here')

        then:
        eventually.eventually { assert sync(main()).startsWith('⚠') }
        json(main()) == jsonBefore
        node(main(), 'Order') != null
    }

    def "a hand-edited JSON is not overwritten when the text changes"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        onEdt {
            def editor = main().@jsonPanel.@editor
            editor.editable = true
            editor.insert(' ', 0)
        }
        def editedJson = json(main())

        when:
        onEdt { main().@textArea.insert(' ', 0) }

        then:
        eventually.eventually { assert sync(main()).startsWith('✔') }
        json(main()) == editedJson
        onEdt { main().@jsonPanel.@lblInfo.text }.contains(Messages.get('json.outdated.edited'))
    }

    def "text → tree: locates the property under the caret (instantiating if needed)"() {
        given:
        setText(main(), LOG)
        int off = LOG.indexOf('prod=B') + 1

        when:
        onEdt { main().locateFromSource(off) }

        then:
        eventually.eventually {
            assert onEdt { tree(main()).selectionPath?.lastPathComponent?.toString() } == 'prod  =  "B-22/x"'
        }
    }

    def "JSON → tree and text at once, without cycles"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def origin = onEdt { main().@jsonPanel.getClientProperty('json.origin') }

        when:
        onEdt {
            main().locateInTree(origin, ['extra', 'k', 'x'])
            main().locateInSource(origin, ['extra', 'k', 'x'])
        }

        then:
        onEdt { tree(main()).selectionPath.lastPathComponent.toString() } == 'x  =  1'
        onEdt { main().@textArea.selectedText } == 'x'
        sync(main()).startsWith('✔')            // no other sync was triggered
    }

    def "analyzing a node opens a tab with its text, its tree and its JSON"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def mainJson = JSON.readTree(json(main()))

        when:
        onEdt { main().extractBranch(node(main(), 'extra')) }
        def branch = onEdt { trios().getComponentAt(1) as TrioPanel }

        then:
        onEdt { trios().tabCount } == 2
        waitInSync(branch)
        text(branch) == '{k=Foo(x=1), z=[1, 2]}'
        JSON.readTree(json(branch)) == mainJson.path('extra')
    }

    def "tabs can be switched back and forth with real clicks; the branch closes and Root does not"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        onEdt { main().extractBranch(node(main(), 'lines')) }

        when:
        def selection = []
        [0, 1, 0, 1].each { i -> clickTab(trios(), i); selection << onEdt { trios().selectedIndex } }

        then:
        selection == [0, 1, 0, 1]

        when: 'closing the branch with its ✕'
        onEdt {
            def header = trios().getTabComponentAt(1) as JPanel
            (header.components.find { it instanceof JButton } as JButton).doClick()
        }

        then:
        onEdt { trios().tabCount } == 1
        onEdt { trios().getTabComponentAt(0) } == null       // Root has no ✕
    }

    def "independent sessions; closing the last one leaves a new empty one"() {
        when:
        def s2 = onEdt { frame.newSession() }
        setText(main(s2), 'Other(a=1)')
        waitInSync(main(s2))

        then:
        text(main()) == ''
        onEdt { sessions().tabCount } == 3                     // 2 sessions + "+"

        when:
        onEdt { frame.closeSession(session(0)); frame.closeSession(session(0)) }

        then:
        onEdt { sessions().tabCount } == 2                     // 1 new session + "+"
        text(main()) == ''
    }

    def "replace all with regex and undo with a single Ctrl+Z"() {
        given:
        setText(main(), 'A(v1=10, v2=20, v3=30)')
        waitInSync(main())
        def fr = main().@sourceSearch

        when:
        onEdt {
            fr.@tglRegex.selected = true
            fr.@txtFind.text = /v(\d)=(\d+)/
            fr.@txtReplace.text = 'w$1=$2'
            fr.replaceAll()
        }

        then:
        text(main()) == 'A(w1=10, w2=20, w3=30)'

        when:
        onEdt { main().@textArea.undoLastAction() }

        then:
        text(main()) == 'A(v1=10, v2=20, v3=30)'
    }

    def "save proposes the folder of the last opened .log, from another session"() {
        given:
        def prefs = Preferences.userNodeForPackage(TrioPanel)
        def previous = prefs.get('lastLogFile', null)
        prefs.put('lastLogFile', folder.resolve('something.log').toString())

        when:
        def s2 = onEdt { frame.newSession() }
        setText(main(s2), 'Other(a=1)')
        def proposal = onEdt { main(s2).proposedJsonFile() } as File

        then:
        proposal.parentFile.toPath() == folder
        proposal.name == Config.string('json.defaultName')

        cleanup:
        previous != null ? prefs.put('lastLogFile', previous) : prefs.remove('lastLogFile')
    }

    def "Wrap is refused with a giant line (without freezing the UI)"() {
        given:
        int length = Config.integer('editor.maxWrapLineLength') + 1000
        setText(main(), 'G(a=' + ('x' * length) + ')')

        when:
        def dialogs = closingDialogs {
            main().@tglWrap.selected = true
            main().toggleWrap()
        }

        then:
        !onEdt { main().@textArea.lineWrap }
        def firstLine = Messages.get('source.wrap.tooLong', length + 'G(a=)'.length()).readLines()[0]
        dialogs.any { it.contains(firstLine) }
    }

    def "node action menu: ghost on hover, fixed on right click, runs an option, Escape closes"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def t = tree(main())
        Point nodePoint = onEdt {
            def n = node(main(), 'customer')
            def r = t.getPathBounds(new javax.swing.tree.TreePath(n.path))
            def p = new Point((r.@x + 20) as int, (r.@y + r.@height.intdiv(2)) as int)
            SwingUtilities.convertPointToScreen(p, t)
            p
        }
        def robot = new Robot()
        def menu = { -> onEdt { frame.layeredPane.getComponentsInLayer(JLayeredPane.POPUP_LAYER).find { it instanceof HoverActionMenu } } }

        when: 'hover over the node'
        robot.mouseMove(nodePoint.@x, nodePoint.@y)
        robot.mouseMove(nodePoint.@x + 2, nodePoint.@y)
        robot.waitForIdle()

        then: 'a translucent menu follows the cursor and does not take the mouse'
        eventually.eventually { assert menu() != null && onEdt { menu().@state.toString() } == 'GHOST' }
        !onEdt { menu().contains(5, 5) }

        when: 'right click on the node'
        robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.waitForIdle()

        then: 'it freezes, becomes opaque and interactive, and the node is selected'
        eventually.eventually {
            assert onEdt { menu().@state.toString() } == 'FIXED'
            assert onEdt { menu().@alpha } == 1f
        }
        onEdt { menu().contains(10, 10) }
        onEdt { t.selectionPath.lastPathComponent.toString() }.startsWith('customer')

        when: 'left click on "Locate" (second option)'
        Point option = onEdt {
            def m = menu()
            def p = new Point(30, 4 + 1 * 24 + 12)
            SwingUtilities.convertPointToScreen(p, m)
            p
        }
        robot.mouseMove(option.@x, option.@y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON1_DOWN_MASK)
        robot.waitForIdle()

        then: 'the action runs (text and JSON) and the menu closes'
        eventually.eventually { assert onEdt { main().@textArea.selectedText } == 'customer' }
        onEdt { main().@jsonPanel.@editor.selectedText } == '"customer"'
        menu() == null

        when: 'open it again and press Escape'
        robot.mouseMove(nodePoint.@x, nodePoint.@y)
        robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.waitForIdle()
        eventually.eventually { assert onEdt { menu()?.@state?.toString() } == 'FIXED' }
        robot.keyPress(java.awt.event.KeyEvent.VK_ESCAPE)
        robot.keyRelease(java.awt.event.KeyEvent.VK_ESCAPE)
        robot.waitForIdle()

        then:
        eventually.eventually { assert menu() == null }
    }

    def "source text: hover over a property offers 'Locate'; right click adds editing actions"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def area = main().@textArea
        Point at = onEdt {
            def r = area.modelToView2D(LOG.indexOf('prod=B') + 2)
            def p = new Point((int) r.x, (int) (r.y + r.height / 2))
            SwingUtilities.convertPointToScreen(p, area)
            p
        }
        def robot = new Robot()
        def menu = { -> onEdt { frame.layeredPane.getComponentsInLayer(JLayeredPane.POPUP_LAYER).find { it instanceof HoverActionMenu } } }
        def texts = { -> onEdt { menu().@items*.text() } }

        when: 'hover over the property'
        robot.mouseMove(at.@x, at.@y)
        robot.mouseMove(at.@x + 1, at.@y)
        robot.waitForIdle()

        then: 'only the specific action, translucent'
        eventually.eventually { assert menu() != null && onEdt { menu().@state.toString() } == 'GHOST' }
        texts() == [Messages.get('menu.locate')]

        when: 'right click'
        robot.mousePress(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.mouseRelease(java.awt.event.InputEvent.BUTTON3_DOWN_MASK)
        robot.waitForIdle()

        then: 'fixed, with the editing actions too'
        eventually.eventually { assert onEdt { menu().@state.toString() } == 'FIXED' }
        texts() == [Messages.get('menu.locate'), Messages.get('edit.undo'), Messages.get('edit.cut'),
                    Messages.get('edit.copy'), Messages.get('edit.paste'), Messages.get('edit.selectAll')]

        when: 'choose "Locate"'
        onEdt { menu().@items[0].action().run(); menu().hideMenu() }

        then: 'tree and JSON'
        eventually.eventually {
            assert onEdt { tree(main()).selectionPath?.lastPathComponent?.toString() } == 'prod  =  "B-22/x"'
        }
        onEdt { main().@jsonPanel.@editor.selectedText } == '"prod"'
    }

    def "JSON: replace becomes available when 'Editable' is checked"() {
        given:
        setText(main(), LOG)
        waitInSync(main())
        def panel = onEdt { main().@jsonPanel }
        def chk = onEdt { findAll(panel, JCheckBox).find { it.text == Messages.get('json.editable') } }
        def fr = onEdt { findAll(panel, JComponent).find { it.getClass().name.endsWith('FlatButton') && it.text == '›' } }

        expect:
        !onEdt { fr.enabled }

        when:
        onEdt { chk.doClick() }

        then:
        onEdt { fr.enabled }

        when: 'open the replace row'
        onEdt { fr.doClick() }
        Thread.sleep(300)
        def btn = onEdt { findAll(panel, JButton).find { it.text == Messages.get('search.replaceAll') } }

        then: 'its buttons are enabled and actually visible (not clipped by the toolbar)'
        onEdt { btn.enabled && btn.showing }
        onEdt {
            def r = SwingUtilities.convertRectangle(btn.parent, btn.bounds, panel)
            r.height > 0 && r.y >= 0 && r.y + r.height <= panel.height && btn.visibleRect.height == btn.height
        }
    }

    static List findAll(Container c, Class type) {
        def out = []
        c.components.each { k ->
            if (type.isInstance(k)) out << k
            if (k instanceof Container) out.addAll(findAll(k, type))
        }
        out
    }

    def "stress: with a ~250,000 character line the UI keeps responding"() {
        given:
        def big = 'X INFO [t] Big(' + (1..9000).collect { "field$it=value$it" }.join(', ') + ')'

        when:
        setText(main(), big)
        waitInSync(main())
        onEdt { main().@sourceSearch.@txtFind.text = 'field8999' }
        Thread.sleep(Config.integer('search.delayMs') + 200)
        long sent = System.nanoTime()
        onEdt { null }                                       // round trip through the EDT
        long latencyMs = (System.nanoTime() - sent).intdiv(1_000_000)

        then:
        latencyMs < 1000
        onEdt { main().@textArea.selectedText } == 'field8999'
    }
}
