package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.model.JsonObjectMap;
import com.logobjectjson.parser.LogExtractor;
import com.logobjectjson.parser.LogExtractor.ParsedEntry;
import com.logobjectjson.ui.fold.JsonPath;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.BadLocationException;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.HierarchyEvent;
import java.awt.event.HierarchyListener;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.prefs.Preferences;
import java.util.stream.Collectors;

/**
 * A text → tree → JSON "trio" (one tab within a session): Root or an
 * extracted branch. Its buttons act only on this tab.
 *
 * Single data source: a text editor (RSyntaxTextArea ⊂ JTextArea).
 *   • The log can be pasted directly or loaded with "Open file…"
 *     (the file content is loaded into the editor; the last path is remembered).
 *   • Find/Replace with history (Ctrl+F / Ctrl+H).
 *   • Live sync: pasting, loading or editing the text re-instantiates it
 *     automatically (a while after the last keystroke) and updates the tree and the JSON.
 *   • The app serializes ONE object: several independent objects in the text
 *     are reported as an error (grouping them would invent structure).
 */
public class TrioPanel extends JPanel {

    /** Asks to open a branch in a new tab: title, path (tooltip) and branch text. */
    @FunctionalInterface
    public interface BranchOpener {
        void open(String title, String path, String text);
    }

    private static final Color SYNC_ERROR = new Color(190, 60, 30);
    private static final Color SYNC_OK = new Color(30, 130, 60);
    /** Max line numbers listed when the text contains several objects. */
    private static final int MAX_LISTED_LINES = 10;
    /** Inserting more than this many characters at once counts as a paste. */
    private static final int PASTE_THRESHOLD = 40;
    private static final int INITIAL_EXPANDED_LEVELS = 3;

    /** Reports this tab's title (file name or "Pasted text"). */
    private final Consumer<String> onTitleChange;
    private final BranchOpener branchOpener;

    /** true if this trio is an extracted branch (keeps its title unless a file is loaded). */
    private boolean isBranch;

    /** The single JSON of this trio (serialization of its tree's object). */
    private JsonViewerPanel jsonPanel;

    /** CardLayout panel: disabled placeholder when there is no serialization, or the JSON. */
    private final JPanel jsonArea = new JPanel(new CardLayout());
    private JsonViewerPanel placeholderJson;

    // ── Data source ────────────────────────────────────────────────────────
    private final RSyntaxTextArea  textArea;
    private final JButton          btnOpen;
    private final JLabel           lblFile;
    private final FindReplacePanel sourceSearch;

    /** Text → tree/JSON sync status indicator (✔ / ⏳ / ⚠). */
    private final JLabel lblSync;
    /** Languages whose printing syntax matches the object (hint, from dialects.yml). */
    private final JLabel lblLanguage = new LanguageBadge();

    /** Automatic re-instantiation after typing stops. */
    private final Timer autoInstantiate = new Timer(Config.integer("sync.delayMs"), e -> instantiateIfChanged());

    /** Client-property key with the stable identity (entry + path) of the JSON's origin. */
    private static final String ORIGIN_ID = "json.originId";
    /** Client-property key with the tree node the JSON was serialized from. */
    private static final String ORIGIN = "json.origin";

    /** Stable node identity across re-instantiations: entry index + JSON path. */
    private record NodeId(int entry, List<Object> path) { }

    private final JToggleButton tglWrap;

    /** Above this line length wrapping is not allowed (it would freeze the UI). */
    private static final int MAX_WRAP_LINE = Config.integer("editor.maxWrapLineLength");

    // ── Result ─────────────────────────────────────────────────────────────
    private final JTree  resultTree;
    private final JLabel lblStatus;

    /** Root of the last result. */
    private DefaultMutableTreeNode root;

    /** Entries and text of the last instantiation (to locate text → node). */
    private List<ParsedEntry> currentEntries;
    private String instantiatedText;

    /** Last loaded file and its content as is (to propose the .json name). */
    private Path loadedFile;
    private String loadedText;

    // ─────────────────────────────────────────────────────────────────────────
    // Constructor
    // ─────────────────────────────────────────────────────────────────────────

    public TrioPanel(Consumer<String> onTitleChange, BranchOpener branchOpener) {
        this.onTitleChange = onTitleChange != null ? onTitleChange : t -> { };
        this.branchOpener = branchOpener;

        // Source editor (pasted text or loaded file)
        textArea = new RSyntaxTextArea(12, 70) {
            /** Gray placeholder text while the editor is empty. */
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                if (getDocument().getLength() > 0) return;
                String placeholder = Messages.get("source.placeholder");
                if (placeholder.isBlank()) return;
                var g2 = (Graphics2D) g.create();
                try {
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                    Color inactive = UIManager.getColor("TextField.inactiveForeground");
                    g2.setColor(inactive != null ? inactive : Color.GRAY);
                    g2.setFont(getFont().deriveFont(Font.ITALIC));
                    var fm = g2.getFontMetrics();
                    Insets in = getInsets();
                    int x = in.left + 4, y = in.top + fm.getAscent();
                    for (String line : placeholder.strip().split("\\R")) {
                        g2.drawString(line, x, y);
                        y += fm.getHeight();
                    }
                } finally {
                    g2.dispose();
                }
            }
        };
        textArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        // No line wrap: with huge lines (200k+ character logs on a single line)
        // RSyntaxTextArea's wrapping is quadratic and freezes the UI.
        textArea.setLineWrap(false);
        textArea.setHighlightCurrentLine(false);
        textArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sourceSearch = new FindReplacePanel(textArea);
        // A single context menu: "Locate in the tree" on hover over a property; + editing on right click
        textArea.setPopupMenu(null);
        HoverActionMenu.install(textArea, this::sourceActionsAt);

        btnOpen = new JButton(new FolderIcon(18));
        btnOpen.setToolTipText(Messages.get("source.open.tip"));
        styleMainButton(btnOpen);
        lblFile = new JLabel(" ");
        lblFile.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        lblFile.setForeground(Color.DARK_GRAY);

        lblSync = new JLabel(" ");
        lblSync.setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
        lblSync.setBorder(BorderFactory.createEmptyBorder(0, 6, 0, 8));
        autoInstantiate.setRepeats(false);

        tglWrap = new JToggleButton(new WrapIcon());
        tglWrap.setToolTipText(Messages.get("source.wrap.tip", MAX_WRAP_LINE));
        tglWrap.setFocusable(false);

        // Result tree
        resultTree = new JTree(new DefaultMutableTreeNode(Messages.get("tree.empty")));
        resultTree.setRootVisible(true);
        resultTree.setShowsRootHandles(true);
        resultTree.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        ToolTipManager.sharedInstance().registerComponent(resultTree);
        resultTree.setCellRenderer(new DefaultTreeCellRenderer() {
            @Override
            public Component getTreeCellRendererComponent(JTree tree, Object value, boolean sel,
                    boolean expanded, boolean leaf, int row, boolean hasFocus) {
                super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
                Object uo = ((DefaultMutableTreeNode) value).getUserObject();
                setToolTipText(uo instanceof TreeNodeValue t ? t.tooltip() : null);
                if (uo instanceof TreeNodeValue t) setIcon(TreeIcons.forValue(t.value()));
                return this;
            }
        });

        lblStatus = new JLabel(" ");
        lblStatus.setFont(lblStatus.getFont().deriveFont(Font.ITALIC));
        lblStatus.setForeground(Color.DARK_GRAY);

        registerListeners();
        buildLayout();
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Listeners
    // ─────────────────────────────────────────────────────────────────────────

    private void registerListeners() {
        textArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e)  { textChanged(); }
            @Override public void removeUpdate(DocumentEvent e)  { textChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        btnOpen.addActionListener(e -> chooseFile());
        tglWrap.addActionListener(e -> toggleWrap());

        // Ctrl+Enter in the editor = sync now, without waiting
        textArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, InputEvent.CTRL_DOWN_MASK), "instantiate");
        textArea.getActionMap().put("instantiate", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) {
                autoInstantiate.stop();
                instantiate(null);
            }
        });

        // Node actions: translucent menu on hover, fixed and interactive on right click
        var nodeMenu = HoverActionMenu.install(resultTree, (p, fixed) -> nodeActionsAt(p));
        resultTree.addMouseListener(new MouseAdapter() {
            @Override public void mousePressed(MouseEvent e)  { selectOnPopup(e); }
            @Override public void mouseReleased(MouseEvent e) { selectOnPopup(e); }
        });
        // Keyboard: Shift+F10 / context-menu key opens the menu on the selected node
        Runnable menuOnSelection = () -> {
            int row = resultTree.getLeadSelectionRow();
            var r = row < 0 ? null : resultTree.getRowBounds(row);
            if (r != null) nodeMenu.fixAt(new Point(r.x + Math.min(r.width, 40), r.y + r.height));
        };
        for (var ks : List.of(KeyStroke.getKeyStroke(KeyEvent.VK_F10, InputEvent.SHIFT_DOWN_MASK),
                              KeyStroke.getKeyStroke(KeyEvent.VK_CONTEXT_MENU, 0))) {
            resultTree.getInputMap().put(ks, "nodeMenu");
        }
        resultTree.getActionMap().put("nodeMenu", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { menuOnSelection.run(); }
        });
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Node actions (tree)
    // ─────────────────────────────────────────────────────────────────────────

    private static final Icon ICON_ANALYZE = new HoverActionMenu.GlyphIcon("⧉", new Color(45, 105, 200));


    /** Tree node under p (only on the row's label/icon), or null. */
    private DefaultMutableTreeNode nodeAt(Point p) {
        int row = resultTree.getRowForLocation(p.x, p.y);
        if (row < 0) return null;
        var node = (DefaultMutableTreeNode) resultTree.getPathForRow(row).getLastPathComponent();
        return node.getUserObject() instanceof TreeNodeValue ? node : null;
    }

    /** Actions available on the node under p (empty if there is no node). */
    private List<HoverActionMenu.Item> nodeActionsAt(Point p) {
        var node = nodeAt(p);
        if (node == null) return List.of();
        Object v = ((TreeNodeValue) node.getUserObject()).value();
        boolean isContainer = (v instanceof DynamicObject || v instanceof Map || v instanceof List) && node != root;
        JsonViewerPanel target = panelContaining(node);
        return List.of(
                new HoverActionMenu.Item(Messages.get("menu.analyzeNode"), ICON_ANALYZE,
                        isContainer && branchOpener != null, Messages.get("menu.analyzeNode.tip"),
                        () -> extractBranch(node)),
                new HoverActionMenu.Item(Messages.get("menu.locate"), HoverActionMenu.ICON_LOCATE,
                        target != null || instantiatedText != null, Messages.get("menu.locate.fromTree.tip"),
                        () -> {
                            if (target != null) locateInJson(target, node);
                            if (instantiatedText != null) locateInSource(node, List.of());
                        }));
    }

    /**
     * Source editor actions at p: "Locate in the tree" when p is over a property of
     * the instantiated object (also offered on right click while out of sync);
     * editing actions on right click.
     */
    private List<HoverActionMenu.Item> sourceActionsAt(Point p, boolean fixed) {
        int off = textOffsetAt(textArea, p);
        boolean synced = currentEntries != null && textArea.getText().equals(instantiatedText);
        boolean onObject = off >= 0 && synced && currentEntries.stream().anyMatch(en -> en.contains(off));
        var locate = onObject || (fixed && off >= 0 && !synced && textArea.getDocument().getLength() > 0)
                ? List.of(new HoverActionMenu.Item(Messages.get("menu.locate"), HoverActionMenu.ICON_LOCATE, true,
                        Messages.get("menu.locate.fromText.tip"), () -> locateFromSource(off)))
                : List.<HoverActionMenu.Item>of();
        return fixed ? HoverActionMenu.concat(locate, HoverActionMenu.editItems(textArea)) : locate;
    }

    /** Text offset under p, or -1 if p is beyond the end of its line. */
    static int textOffsetAt(JTextArea area, Point p) {
        int off = area.viewToModel2D(p);
        if (off < 0) return -1;
        try {
            int line = area.getLineOfOffset(off);
            int end = area.getLineEndOffset(line);
            var r = area.modelToView2D(Math.max(area.getLineStartOffset(line), end - 1));
            if (r != null && p.x > r.getX() + r.getWidth() + 8) return -1;
        } catch (BadLocationException ex) {
            return -1;
        }
        return off;
    }

    /** A right click also selects the node it was made on. */
    private void selectOnPopup(MouseEvent e) {
        if (!e.isPopupTrigger()) return;
        var node = nodeAt(e.getPoint());
        if (node != null) resultTree.setSelectionPath(new TreePath(node.getPath()));
    }

    private void serializeNode(DefaultMutableTreeNode node) {
        var tnv = (TreeNodeValue) node.getUserObject();
        try {
            String json = JsonSerializer.toPrettyJson(tnv.value());
            var panel = new JsonViewerPanel[1];
            panel[0] = new JsonViewerPanel(json,
                    path -> {
                        var origin = (DefaultMutableTreeNode) panel[0].getClientProperty(ORIGIN);
                        locateInTree(origin, path);
                        locateInSource(origin, path);   // only selects: triggers no further search
                    });
            panel[0].setProposedFile(this::proposedJsonFile);
            panel[0].putClientProperty(ORIGIN, node);
            panel[0].putClientProperty(ORIGIN_ID, idOf(node));
            panel[0].setTooltipFormatter(path -> {
                var origin = (DefaultMutableTreeNode) panel[0].getClientProperty(ORIGIN);
                Object rootValue = ((TreeNodeValue) origin.getUserObject()).value();
                return JsonPath.format(path, i -> containerMark(valueAt(rootValue, path.subList(0, i + 1))));
            });
            showJson(panel[0]);
            lblStatus.setText(Messages.get("status.jsonGenerated", tnv.label()));
        } catch (Exception ex) {
            showError(Messages.get("error.serialize", ex.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree → JSON
    // ─────────────────────────────────────────────────────────────────────────

    /** The JSON panel, if its origin node contains the given node. */
    private JsonViewerPanel panelContaining(DefaultMutableTreeNode node) {
        if (jsonPanel == null) return null;
        var origin = (DefaultMutableTreeNode) jsonPanel.getClientProperty(ORIGIN);
        return origin != null && (origin == node || origin.isNodeDescendant(node)) ? jsonPanel : null;
    }

    private void locateInJson(JsonViewerPanel panel, DefaultMutableTreeNode node) {
        var origin = (DefaultMutableTreeNode) panel.getClientProperty(ORIGIN);
        List<Object> path = jsonPath(origin, node);
        if (panel.locatePath(path)) {
            lblStatus.setText(Messages.get("status.locatedInJson",
                    path.isEmpty() ? Messages.get("json.root") : JsonPath.format(path)));
        } else {
            lblStatus.setText(Messages.get("status.pathNotInJson"));
        }
    }

    /**
     * JSON path from the origin node (the serialized one) to the target node.
     * Each container → child step contributes a key or an index.
     */
    private static List<Object> jsonPath(DefaultMutableTreeNode origin, DefaultMutableTreeNode target) {
        var path = new ArrayList<Object>();
        var n = target;
        while (n != origin && n.getParent() != null) {
            var p = (DefaultMutableTreeNode) n.getParent();
            if (p.getUserObject() instanceof TreeNodeValue pv
                    && n.getUserObject() instanceof TreeNodeValue nv
                    && pv.value() != nv.value()) {
                Object seg = segment(pv.value(), p.getIndex(n));
                if (seg != null) path.add(0, seg);
            }
            n = p;
        }
        return path;
    }

    /** Value reached by walking the path from v (null if it does not exist). */
    private static Object valueAt(Object v, List<Object> path) {
        for (Object seg : path) {
            if (v instanceof DynamicObject obj) v = obj.properties();
            if (v instanceof Map<?, ?> map && seg instanceof String k) {
                v = map.get(k);
            } else if (v instanceof List<?> list && seg instanceof Integer i && i < list.size()) {
                v = list.get(i);
            } else {
                return null;
            }
        }
        return v;
    }

    /** Mark for the tooltip path: "<,>" Java Map, "{}" JSON object, nothing otherwise. */
    private static String containerMark(Object v) {
        if (v instanceof JsonObjectMap) return "{}";
        if (v instanceof Map<?, ?>) return "<,>";
        return null;
    }

    /** Key (object/map) or index (list) of the child at position idx. */
    private static Object segment(Object value, int idx) {
        if (value instanceof DynamicObject obj) value = obj.properties();
        if (value instanceof Map<?, ?> map) {
            int i = 0;
            for (Object k : map.keySet()) {
                if (i++ == idx) return String.valueOf(k);
            }
            return null;
        }
        if (value instanceof List<?>) return idx;
        return null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // JSON → tree
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Collapses the whole tree and expands only the path to the node matching
     * the JSON path, starting from the serialized node.
     */
    private void locateInTree(DefaultMutableTreeNode origin, List<Object> path) {
        var target = findNode(origin, path);
        if (target == null) {
            lblStatus.setText(Messages.get("status.notInTree", JsonPath.format(path)));
            return;
        }
        collapseAll();
        var treePath = new TreePath(((DefaultTreeModel) resultTree.getModel()).getPathToRoot(target));
        resultTree.setSelectionPath(treePath);
        centerInTree(treePath);
        lblStatus.setText(Messages.get("status.located", JsonPath.format(path)));
    }

    /** Descends through nodes that share their value with their only child. */
    private static DefaultMutableTreeNode container(DefaultMutableTreeNode node) {
        while (node.getChildCount() == 1) {
            var child = (DefaultMutableTreeNode) node.getChildAt(0);
            if (!(child.getUserObject() instanceof TreeNodeValue c)
                    || !(node.getUserObject() instanceof TreeNodeValue p)
                    || c.value() != p.value()) break;
            node = child;
        }
        return node;
    }

    /** Child position for a segment (key or index) according to the container's value. */
    private static int childIndex(Object value, Object segment) {
        if (value instanceof DynamicObject obj) value = obj.properties();
        if (value instanceof Map<?, ?> map && segment instanceof String key) {
            int i = 0;
            for (Object k : map.keySet()) {
                if (String.valueOf(k).equals(key)) return i;
                i++;
            }
            return -1;
        }
        if (value instanceof List<?> && segment instanceof Integer i) return i;
        return -1;
    }

    /** Makes the path visible and centers its row vertically in the tree. */
    private void centerInTree(TreePath path) {
        resultTree.scrollPathToVisible(path);
        var r = resultTree.getPathBounds(path);
        if (r == null) return;
        var vis = resultTree.getVisibleRect();
        int y = Math.max(0, r.y - (vis.height - r.height) / 2);
        resultTree.scrollRectToVisible(new Rectangle(vis.x, y, vis.width, vis.height));
    }

    private void collapseAll() {
        for (int i = resultTree.getRowCount() - 1; i >= 1; i--) {
            resultTree.collapseRow(i);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree search
    // ─────────────────────────────────────────────────────────────────────────

    /** Searches the labels of every node (even collapsed ones). */
    private String searchTree(java.util.regex.Pattern pattern, boolean forward, boolean fromStart) {
        var treeRoot = (DefaultMutableTreeNode) resultTree.getModel().getRoot();

        var hits = new ArrayList<DefaultMutableTreeNode>();
        for (var en = treeRoot.preorderEnumeration(); en.hasMoreElements(); ) {
            var n = (DefaultMutableTreeNode) en.nextElement();
            if (pattern.matcher(String.valueOf(n.getUserObject())).find()) hits.add(n);
        }
        if (hits.isEmpty()) return null;

        int current = -1;
        var sel = resultTree.getSelectionPath();
        if (sel != null && !fromStart) current = hits.indexOf(sel.getLastPathComponent());
        int idx;
        if (fromStart) {
            idx = 0;
        } else if (current >= 0) {
            idx = Math.floorMod(current + (forward ? 1 : -1), hits.size());
        } else {
            idx = forward ? 0 : hits.size() - 1;
        }

        var full = hits.get(idx).getPath();
        int from = Arrays.asList(full).indexOf(treeRoot);
        var path = new TreePath(Arrays.copyOfRange(full, from, full.length));

        resultTree.setSelectionPath(path);
        centerInTree(path);
        return (idx + 1) + " / " + hits.size();
    }

    /** Sets the panel as this trio's JSON (replaces the previous one). */
    private void showJson(JsonViewerPanel panel) {
        if (jsonPanel != null) jsonArea.remove(jsonPanel);
        jsonPanel = panel;
        jsonArea.add(panel, "json");
        ((CardLayout) jsonArea.getLayout()).show(jsonArea, "json");
        jsonArea.revalidate();
    }

    /** Removes the JSON and shows the disabled placeholder with a message. */
    private void removeJson(String message) {
        if (jsonPanel != null) jsonArea.remove(jsonPanel);
        jsonPanel = null;
        placeholderJson.showDisabled(message);
        ((CardLayout) jsonArea.getLayout()).show(jsonArea, "empty");
    }

    /** The object the JSON serializes: the single entry of the text. */
    private DefaultMutableTreeNode jsonOrigin() {
        return root == null || root.getChildCount() != 1 ? null : (DefaultMutableTreeNode) root.getChildAt(0);
    }

    /** Creates the JSON if missing, or rebuilds it if its origin changed. */
    private void ensureJson() {
        if (jsonPanel != null && jsonPanel.isModified()) return;   // do not discard hand edits
        var origin = jsonOrigin();
        if (origin == null) {
            removeJson(Messages.get("json.none"));
            return;
        }
        if (jsonPanel == null || !Objects.equals(jsonPanel.getClientProperty(ORIGIN_ID), idOf(origin))) {
            serializeNode(origin);
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Layout
    // ─────────────────────────────────────────────────────────────────────────

    private void buildLayout() {
        var content = new JPanel(new BorderLayout(8, 8));
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
        setLayout(new BorderLayout());
        add(content, BorderLayout.CENTER);

        // Vertical JSplitPane: data source (top) | result (bottom)
        var vertical = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sourcePanel(), resultPanel());
        vertical.setResizeWeight(0.0);
        vertical.setContinuousLayout(true);
        vertical.setOneTouchExpandable(true);
        content.add(vertical, BorderLayout.CENTER);
        content.add(statusPanel(), BorderLayout.SOUTH);
    }

    /** Top panel: data source. */
    private JPanel sourcePanel() {
        var panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createTitledBorder(Messages.get("source.title")),
                BorderFactory.createEmptyBorder(2, 6, 4, 6)));

        // Toolbar: sync | [📂] file | [›] 🔍 [find ▾] Cc W .* n of m ↑ ↓ … [wrap]
        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.add(lblSync);
        bar.add(lblLanguage);
        bar.addSeparator();
        bar.add(EditToggle.create(textArea, true, Messages.get("source.editable.tip"), null));
        bar.add(btnOpen);
        bar.add(lblFile);
        bar.addSeparator();
        bar.add(sourceSearch.getPanel());
        bar.add(Box.createHorizontalGlue());
        bar.add(tglWrap);                       // "Wrap Lines" on the right, like Postman

        var scroll = new RTextScrollPane(textArea);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(false);
        scroll.setMinimumSize(new Dimension(100, 60));

        panel.add(bar, BorderLayout.NORTH);
        panel.add(scroll, BorderLayout.CENTER);
        return panel;
    }

    /** Bottom panel: result tree | JSON. */
    private JPanel resultPanel() {
        var treePanel = new JPanel(new BorderLayout(0, JsonViewerPanel.TOOLBAR_GAP));
        treePanel.setBorder(BorderFactory.createTitledBorder(Messages.get("tree.title")));
        // The tree's search bar is as tall as the JSON toolbar next to it (aligned rows)
        var treeBar = new JToolBar() {
            @Override public Dimension getPreferredSize() {
                var d = super.getPreferredSize();
                return new Dimension(d.width, Math.max(d.height, placeholderJson.toolbarHeight()));
            }
        };
        treeBar.setFloatable(false);
        treeBar.setRollover(true);
        treeBar.add(new TreeSearchBar(Messages.get("treeSearch.placeholder"), this::searchTree));
        treePanel.add(treeBar, BorderLayout.NORTH);
        treePanel.add(new JScrollPane(resultTree), BorderLayout.CENTER);

        var jsonBox = new JPanel(new BorderLayout());
        jsonBox.setBorder(BorderFactory.createTitledBorder(Messages.get("json.title")));
        placeholderJson = new JsonViewerPanel("", null);
        placeholderJson.showDisabled(Messages.get("json.none"));
        jsonArea.add(placeholderJson, "empty");
        jsonBox.add(jsonArea, BorderLayout.CENTER);

        treePanel.setPreferredSize(new Dimension(460, 300));   // reasonable initial tree width
        var split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, treePanel, jsonBox);
        split.setResizeWeight(0.4);
        split.setContinuousLayout(true);
        // The JSON panel must not impose its minimum width (its toolbar gets clipped);
        // when first shown, the tree takes ~38% of the width.
        jsonBox.setMinimumSize(new Dimension(200, 100));
        treePanel.setMinimumSize(new Dimension(150, 100));
        split.addHierarchyListener(new HierarchyListener() {
            @Override public void hierarchyChanged(HierarchyEvent e) {
                if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0 && split.isShowing()) {
                    split.removeHierarchyListener(this);
                    SwingUtilities.invokeLater(() -> split.setDividerLocation(0.38));
                }
            }
        });

        var panel = new JPanel(new BorderLayout());
        panel.add(split, BorderLayout.CENTER);
        return panel;
    }

    /** Bottom status bar. */
    private JPanel statusPanel() {
        var panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createEmptyBorder(2, 4, 0, 4));
        panel.add(lblStatus, BorderLayout.WEST);
        return panel;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Actions
    // ─────────────────────────────────────────────────────────────────────────

    private static final String PREF_LAST_FILE = "lastLogFile";

    private void chooseFile() {
        // Remember the folder (and file) of the last selection across runs
        var prefs = Preferences.userNodeForPackage(TrioPanel.class);
        String last = prefs.get(PREF_LAST_FILE, null);
        var chooser = new JFileChooser();
        if (last != null) {
            var f = new File(last);
            if (f.getParentFile() != null && f.getParentFile().isDirectory()) {
                chooser.setCurrentDirectory(f.getParentFile());
                if (f.isFile()) chooser.setSelectedFile(f);
            }
        }
        chooser.setDialogTitle(Messages.get("source.open.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(Messages.get("source.open.filter"), "log"));
        chooser.setAcceptAllFileFilterUsed(true);

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            var file = chooser.getSelectedFile();
            prefs.put(PREF_LAST_FILE, file.getAbsolutePath());
            loadFile(file.toPath());
        }
    }

    /** Loads the file into the editor (UTF-8; if invalid, ISO-8859-1). */
    private void loadFile(Path path) {
        String content;
        try {
            try {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } catch (MalformedInputException ex) {
                content = Files.readString(path, StandardCharsets.ISO_8859_1);
            }
        } catch (IOException ex) {
            showError(Messages.get("error.readFile", ex.getMessage()));
            return;
        }
        if (tglWrap.isSelected()) {          // never load with wrapping on
            tglWrap.setSelected(false);
            textArea.setLineWrap(false);
        }
        removeJson(Messages.get("json.none"));   // new document: the previous serialization does not apply
        textArea.setText(content);
        loadedFile = path;
        loadedText = textArea.getText();     // normalized by the editor (line breaks)
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        lblFile.setText(path.getFileName().toString());
        onTitleChange.accept(path.getFileName().toString());
        lblFile.setToolTipText(path.toAbsolutePath().toString());
        // Fixed size (with margin) so the search box does not clip it
        lblFile.setPreferredSize(null);
        var d = lblFile.getPreferredSize();
        d = new Dimension(d.width + 24, d.height);
        lblFile.setPreferredSize(d);
        lblFile.setMinimumSize(d);
        lblFile.setMaximumSize(d);
        lblStatus.setText(Messages.get("status.fileLoaded", path.toAbsolutePath(), textArea.getLineCount()));
        autoInstantiate.stop();
        instantiate(null);     // opening a file instantiates immediately
    }

    /** Shared look for the main toolbar buttons. */
    private static void styleMainButton(JButton b) {
        b.setFont(UIManager.getFont("Button.font").deriveFont(Font.BOLD));
        b.setIconTextGap(6);
        b.setMargin(new Insets(3, 8, 3, 10));
        b.setFocusable(false);
    }

    /** Turns line wrap on/off, unless there are lines that are too long. */
    private void toggleWrap() {
        if (tglWrap.isSelected()) {
            int max = 0;
            for (int i = 0; i < textArea.getLineCount(); i++) {
                try {
                    max = Math.max(max, textArea.getLineEndOffset(i) - textArea.getLineStartOffset(i));
                } catch (BadLocationException ignored) { }
            }
            if (max > MAX_WRAP_LINE) {
                tglWrap.setSelected(false);
                JOptionPane.showMessageDialog(this, Messages.get("source.wrap.tooLong", max),
                        Messages.get("source.wrap.title"), JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }
        textArea.setLineWrap(tglWrap.isSelected());
    }

    /** On text change: repaint (placeholder), mark as pending and reschedule the sync. */
    private void textChanged() {
        textArea.repaint();
        if (textArea.getDocument().getLength() == 0) {
            autoInstantiate.stop();
            sync(" ", null);
            return;
        }
        if (!textArea.getText().equals(instantiatedText)) {
            sync(Messages.get("sync.updating"), Messages.get("sync.updating.tip"));
            autoInstantiate.restart();
        }
    }

    private void instantiateIfChanged() {
        if (textArea.getDocument().getLength() > 0 && !textArea.getText().equals(instantiatedText)) {
            instantiate(null);
        }
    }

    private void sync(String text, String tip) {
        lblLanguage.setVisible(false);          // shown again only after a successful sync
        lblSync.setText(text);
        lblSync.setToolTipText(tip);
        lblSync.setForeground(text.startsWith("⚠") ? SYNC_ERROR : text.startsWith("✔") ? SYNC_OK : Color.DARK_GRAY);
    }

    /** Shows the languages of the recognized dialect (e.g. "Java · Kotlin") next to the sync status. */
    private void showLanguages(String dialectName) {
        if (dialectName == null) return;
        var langs = com.logobjectjson.parser.Dialect.byName(dialectName).languages();
        lblLanguage.setText(String.join(" · ", langs));
        lblLanguage.setToolTipText(Messages.get("source.language.tip", String.join(", ", langs), dialectName));
        lblLanguage.setVisible(true);
    }

    /** Small rounded badge (light background) for the recognized languages. */
    private static final class LanguageBadge extends JLabel {
        private static final Color BG = new Color(228, 236, 250);
        private static final Color BORDER = new Color(150, 170, 210);

        LanguageBadge() {
            setVisible(false);
            setForeground(new Color(40, 70, 140));
            setFont(UIManager.getFont("Label.font").deriveFont(Font.BOLD));
            setBorder(BorderFactory.createEmptyBorder(1, 8, 1, 8));
        }

        @Override protected void paintComponent(Graphics g) {
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int h = Math.min(getHeight() - 2, getFontMetrics(getFont()).getHeight() + 4);
            int y = (getHeight() - h) / 2;
            g2.setColor(BG);
            g2.fillRoundRect(0, y, getWidth() - 1, h, h, h);
            g2.setColor(BORDER);
            g2.drawRoundRect(0, y, getWidth() - 1, h, h, h);
            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** Line number (1-based) of a text offset. */
    private static int lineOf(String text, int offset) {
        int n = 1;
        for (int i = 0, end = Math.min(offset, text.length()); i < end; i++) if (text.charAt(i) == '\n') n++;
        return n;
    }

    /**
     * Parses the editor content in the background and builds the tree.
     * @param after action to run (on the EDT) if the instantiation succeeded, or null
     */
    private void instantiate(Runnable after) {
        String content = textArea.getText();
        if (content.trim().isEmpty()) return;

        sync(Messages.get("sync.updating"), null);

        // Parse on a worker thread so the EDT is not blocked
        SwingWorker<List<ParsedEntry>, Void> worker = new SwingWorker<>() {
            @Override
            protected List<ParsedEntry> doInBackground() {
                return LogExtractor.extractAll(content);
            }

            @Override
            protected void done() {
                if (!content.equals(textArea.getText())) return;   // the text kept changing
                try {
                    List<ParsedEntry> entries = get();
                    var valid = entries.stream().filter(en -> !en.isError()).toList();
                    if (valid.isEmpty()) {
                        // Keep the last valid version
                        instantiatedText = null;
                        sync(Messages.get("sync.noObjects"), Messages.get(entries.isEmpty()
                                ? "sync.noObjects.none.tip" : "sync.noObjects.error.tip"));
                        return;
                    }

                    // The app serializes ONE object: several independent objects are an error
                    // (grouping them in an array or under a common root would invent structure).
                    if (valid.size() > 1) {
                        showSeveralObjects(content, valid);
                        return;
                    }

                    var state = captureTreeState();
                    var newRoot = ObjectTreeBuilder.buildRoot(valid);
                    root = newRoot;
                    currentEntries = valid;
                    instantiatedText = content;
                    resultTree.setModel(new DefaultTreeModel(newRoot));
                    if (state == null) expandFirstLevels(INITIAL_EXPANDED_LEVELS);
                    else restoreTreeState(state);
                    refreshJson();
                    ensureJson();
                    boolean intact = loadedFile != null && content.equals(loadedText);
                    if (intact) onTitleChange.accept(loadedFile.getFileName().toString());
                    else if (!isBranch) onTitleChange.accept(Messages.get("trio.pastedText"));

                    String dialect = valid.get(0).dialect();
                    String detail = dialect == null ? Messages.get("status.instantiated")
                            : Messages.get("status.instantiated.dialect", dialect);
                    sync(Messages.get("sync.ok"), detail);
                    showLanguages(dialect);
                    lblStatus.setText("✔  " + detail);
                } catch (Exception ex) {
                    Throwable cause = ex.getCause() != null ? ex.getCause() : ex;
                    cause.printStackTrace();
                    instantiatedText = null;
                    sync(Messages.get("sync.parseError"), String.valueOf(cause));
                }
            }
        };
        worker.execute();
        if (after != null) {
            worker.addPropertyChangeListener(ev -> {
                if ("state".equals(ev.getPropertyName()) && ev.getNewValue() == SwingWorker.StateValue.DONE) {
                    // done() already ran on the EDT; run after it
                    SwingUtilities.invokeLater(() -> {
                        if (content.equals(instantiatedText)) after.run();
                    });
                }
            });
        }
    }

    /** Reports that the text holds several independent objects: no tree and no JSON. */
    private void showSeveralObjects(String content, List<ParsedEntry> valid) {
        String lines = valid.stream()
                .map(en -> en.baseOffset() < 0 ? "?" : String.valueOf(lineOf(content, en.baseOffset())))
                .distinct().limit(MAX_LISTED_LINES)
                .collect(Collectors.joining(", "))
                + (valid.size() > MAX_LISTED_LINES ? ", …" : "");
        String msg = Messages.get("error.severalObjects", valid.size(), lines);
        root = null;
        currentEntries = List.of();
        instantiatedText = null;
        resultTree.setModel(new DefaultTreeModel(
                new DefaultMutableTreeNode(Messages.get("tree.severalObjects", valid.size()))));
        removeJson(Messages.get("json.severalObjects", valid.size()));
        sync(Messages.get("sync.severalObjects"), msg);
        lblStatus.setText("⚠  " + msg);
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Sync: stable node identity, tree state, JSON
    // ─────────────────────────────────────────────────────────────────────────

    /** Identity (entry + path) of a node of the current tree; null for the root. */
    private NodeId idOf(DefaultMutableTreeNode node) {
        if (root == null || node == root) return null;
        var entry = node;
        while (entry.getParent() != null && entry.getParent() != root) {
            entry = (DefaultMutableTreeNode) entry.getParent();
        }
        if (entry.getParent() != root) return null;
        return new NodeId(root.getIndex(entry), jsonPath(entry, node));
    }

    /** Node of the current tree with that identity, or null if it no longer exists. */
    private DefaultMutableTreeNode nodeOf(NodeId id) {
        if (id == null) return root;
        if (id.entry() < 0 || id.entry() >= root.getChildCount()) return null;
        return findNode((DefaultMutableTreeNode) root.getChildAt(id.entry()), id.path());
    }

    /** Descends from an origin node following the JSON path; null if it does not exist. */
    private static DefaultMutableTreeNode findNode(DefaultMutableTreeNode origin, List<Object> path) {
        DefaultMutableTreeNode node = container(origin);
        DefaultMutableTreeNode target = node;
        for (int s = 0; s < path.size(); s++) {
            Object value = ((TreeNodeValue) node.getUserObject()).value();
            int idx = childIndex(value, path.get(s));
            if (idx < 0 || idx >= node.getChildCount()) return null;
            target = (DefaultMutableTreeNode) node.getChildAt(idx);
            if (s < path.size() - 1) node = container(target);
        }
        return target;
    }

    /** Visual tree state, expressed with stable identities. */
    private record TreeState(List<NodeId> expanded, NodeId selection) { }

    private TreeState captureTreeState() {
        if (root == null) return null;
        var expanded = new ArrayList<NodeId>();
        var en = resultTree.getExpandedDescendants(new TreePath(root));
        if (en != null) {
            while (en.hasMoreElements()) {
                var n = (DefaultMutableTreeNode) en.nextElement().getLastPathComponent();
                if (n != root) expanded.add(idOf(n));
            }
        }
        var sel = resultTree.getSelectionPath();
        NodeId selection = sel == null ? null : idOf((DefaultMutableTreeNode) sel.getLastPathComponent());
        return new TreeState(expanded, selection);
    }

    private void restoreTreeState(TreeState s) {
        var model = (DefaultTreeModel) resultTree.getModel();
        resultTree.expandPath(new TreePath(root));
        for (NodeId id : s.expanded()) {
            var n = nodeOf(id);
            if (n != null) resultTree.expandPath(new TreePath(model.getPathToRoot(n)));
        }
        if (s.selection() != null) {
            var n = nodeOf(s.selection());
            if (n != null) resultTree.setSelectionPath(new TreePath(model.getPathToRoot(n)));
        }
    }

    /** Regenerates the JSON with the equivalent object of the new tree. */
    private void refreshJson() {
        if (jsonPanel == null) return;
        var id = (NodeId) jsonPanel.getClientProperty(ORIGIN_ID);
        var node = nodeOf(id);
        if (node == null || !(node.getUserObject() instanceof TreeNodeValue tnv)) {
            jsonPanel.markOutdated(Messages.get("json.outdated.gone"));
            return;
        }
        jsonPanel.putClientProperty(ORIGIN, node);
        if (jsonPanel.isModified()) {
            jsonPanel.markOutdated(Messages.get("json.outdated.edited"));
            return;
        }
        try {
            jsonPanel.updateJson(JsonSerializer.toPrettyJson(tnv.value()));
        } catch (Exception ex) {
            jsonPanel.markOutdated(Messages.get("json.outdated.error", ex.getMessage()));
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Tree / JSON → source text
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Selects and centers in the source editor the property matching
     * (origin node + relative path). Only moves the selection: it triggers no
     * search or locate back, so there are no cycles.
     */
    private void locateInSource(DefaultMutableTreeNode origin, List<Object> relativePath) {
        if (currentEntries == null || !textArea.getText().equals(instantiatedText)) {
            lblStatus.setText(Messages.get("status.textChanged.locate"));
            return;
        }
        NodeId id = idOf(origin);
        if (id == null) {                      // origin = root: the first segment is the entry
            if (relativePath.isEmpty() || !(relativePath.get(0) instanceof Integer i)) return;
            id = new NodeId(i, List.of());
            relativePath = relativePath.subList(1, relativePath.size());
        }
        var fullPath = new ArrayList<Object>(id.path());
        fullPath.addAll(relativePath);

        var entry = currentEntries.get(id.entry());
        int start, end;
        if (fullPath.isEmpty()) {
            start = entry.baseOffset();
            end = start + 1;
        } else {
            var span = entry.spans().stream().filter(sp -> sp.path().equals(fullPath)).findFirst().orElse(null);
            if (span == null) {
                lblStatus.setText(Messages.get("status.notInText", JsonPath.format(fullPath)));
                return;
            }
            start = span.start();
            Object last = fullPath.get(fullPath.size() - 1);
            // Select the key (for a property) or the whole element (for an index)
            end = last instanceof String k && textArea.getText().startsWith(k, start)
                    ? start + k.length() : span.end();
        }
        textArea.select(start, end);
        textArea.getCaret().setSelectionVisible(true);
        Centering.on(textArea, start);
    }

    /**
     * Extracts the node's branch to a new tab: its text is exactly the fragment
     * of this tab's text that corresponds to the node's value.
     */
    private void extractBranch(DefaultMutableTreeNode node) {
        if (currentEntries == null || !textArea.getText().equals(instantiatedText)) {
            lblStatus.setText(Messages.get("status.textChanged.extract"));
            return;
        }
        NodeId id = idOf(node);
        if (id == null) return;
        var entry = currentEntries.get(id.entry());
        String text = textArea.getText();
        int start, end;
        if (id.path().isEmpty()) {
            start = entry.baseOffset();
            end = entry.endOffset();
        } else {
            var span = entry.spans().stream().filter(sp -> sp.path().equals(id.path())).findFirst().orElse(null);
            if (span == null) {
                lblStatus.setText(Messages.get("status.branchNotFound"));
                return;
            }
            start = valueStart(text, span.start(), id.path().get(id.path().size() - 1));
            end = span.end();
        }
        String fragment = text.substring(start, end).trim();
        String title = ((TreeNodeValue) node.getUserObject()).label().replaceFirst("\\s*\\(\\d+\\)$", "");
        String path = id.path().isEmpty() ? title : JsonPath.format(id.path());
        branchOpener.open(title, path, fragment);
        lblStatus.setText(Messages.get("status.branchExtracted", path));
    }

    /**
     * Start of the VALUE within a property's span: skips the key (quoted or not)
     * and the separator ('=' or ':'). For list elements the span already is the value.
     */
    private static int valueStart(String text, int spanStart, Object lastSegment) {
        if (!(lastSegment instanceof String)) return spanStart;
        int p = spanStart;
        if (p < text.length() && text.charAt(p) == '"') {             // quoted JSON key
            p++;
            while (p < text.length() && text.charAt(p) != '"') p += text.charAt(p) == '\\' ? 2 : 1;
            p++;
        } else {
            while (p < text.length() && text.charAt(p) != '=' && text.charAt(p) != ':') p++;
        }
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
        if (p < text.length() && (text.charAt(p) == '=' || text.charAt(p) == ':')) p++;
        while (p < text.length() && Character.isWhitespace(text.charAt(p))) p++;
        return p;
    }

    /** Loads a text (e.g. an extracted branch) and instantiates it immediately. */
    public void loadText(String text) {
        isBranch = true;
        removeJson(Messages.get("json.none"));
        textArea.setText(text);
        textArea.setCaretPosition(0);
        textArea.discardAllEdits();
        autoInstantiate.stop();
        instantiate(null);
    }

    /**
     * File proposed when saving the JSON: the loaded .log with the .json extension,
     * unless the text was pasted or edited since → default name (config: json.defaultName).
     */
    private File proposedJsonFile() {
        // Folder: ALWAYS that of the last opened .log, from any session or tab
        // (the same global preference "Open file…" uses).
        File dir = lastLogFolder();

        boolean intact = loadedFile != null && textArea.getText().equals(loadedText);
        String name;
        if (intact) {
            String n = loadedFile.getFileName().toString();
            int dot = n.lastIndexOf('.');
            name = (dot > 0 ? n.substring(0, dot) : n) + Config.string("json.extension");
        } else {
            name = Config.string("json.defaultName");
        }
        return new File(dir, name);
    }

    /** Folder of the last opened .log (global preference), or null if none or it no longer exists. */
    private static File lastLogFolder() {
        String last = Preferences.userNodeForPackage(TrioPanel.class).get(PREF_LAST_FILE, null);
        if (last == null) return null;
        var folder = new File(last).getParentFile();
        return folder != null && folder.isDirectory() ? folder : null;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Source → tree
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Locates the property/element at the source editor offset in the tree and
     * in the JSON. If the content is not instantiated (or changed), instantiates it first.
     */
    private void locateFromSource(int offset) {
        if (currentEntries == null || !textArea.getText().equals(instantiatedText)) {
            lblStatus.setText(Messages.get("status.instantiatingToLocate"));
            instantiate(() -> locateFromSource(offset));
            return;
        }
        for (int i = 0; i < currentEntries.size(); i++) {
            var entry = currentEntries.get(i);
            if (!entry.contains(offset)) continue;
            List<Object> path = entry.pathAt(offset);
            var entryNode = (DefaultMutableTreeNode) root.getChildAt(i);
            locateInTree(entryNode, path);
            var selected = resultTree.getSelectionPath();
            if (selected != null) {
                var node = (DefaultMutableTreeNode) selected.getLastPathComponent();
                var panel = panelContaining(node);
                if (panel != null) locateInJson(panel, node);
            }
            resultTree.requestFocusInWindow();
            return;
        }
        lblStatus.setText(Messages.get("status.notInObject"));
    }

    // ─────────────────────────────────────────────────────────────────────────
    // UI utilities
    // ─────────────────────────────────────────────────────────────────────────

    /** Expands the first N levels of the tree. */
    private void expandFirstLevels(int maxLevel) {
        for (int i = 0; i < resultTree.getRowCount(); i++) {
            var path = resultTree.getPathForRow(i);
            if (path != null && path.getPathCount() <= maxLevel) {
                resultTree.expandRow(i);
            }
        }
    }

    private void showError(String message) {
        JOptionPane.showMessageDialog(this, message, Messages.get("error.title"), JOptionPane.ERROR_MESSAGE);
    }
}
