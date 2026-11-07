package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import com.logobjectjson.compare.MergedJson;
import com.logobjectjson.compare.ObjectDiff;
import com.logobjectjson.compare.ObjectDiff.Node;
import com.logobjectjson.compare.ObjectDiff.Status;
import com.logobjectjson.model.DynamicObject;
import com.logobjectjson.parser.LogExtractor;
import com.logobjectjson.parser.LogExtractor.ParsedEntry;
import com.logobjectjson.ui.fold.JsonEditor;
import com.logobjectjson.ui.fold.JsonPath;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.DefaultHighlighter;
import javax.swing.text.Highlighter;
import javax.swing.text.JTextComponent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.io.File;
import java.io.IOException;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/**
 * Comparison mode of a trio (button ⇄ on the Root tab): the trio's text becomes
 * the PATTERN (locked, the log that works) and a SAMPLE editor opens next to it
 * (the log being analyzed). The tree and the JSON show one combined comparison:
 *
 *   − red   missing in the Sample        (highlighted in the Pattern text)
 *   + green extra in the Sample          (highlighted in the Sample text)
 *   ≠ blue  different type               (highlighted in both)
 *   ~ gray  different value (optional)   (highlighted in both)
 *
 * The comparison JSON is for viewing only; "Save" offers the real JSON of the
 * Pattern or of the Sample. Nothing is invented: only what both texts contain.
 */
final class CompareController {

    /** Tree node of the comparison (rendered with its status color and symbol). */
    record DiffTreeValue(String label, Object value, String tooltip, Status status, int inside, List<Object> path) {
        @Override public String toString() { return label; }
    }

    private static final Map<Status, Color> COLORS = new EnumMap<>(Status.class);
    private static final Map<Status, String> SYMBOLS = Map.of(
            Status.MISSING, "−", Status.EXTRA, "+", Status.TYPE, "≠", Status.VALUE, "~", Status.SAME, "");
    private static final int HIGHLIGHT_ALPHA = Config.integer("compare.highlightAlpha");

    static {
        COLORS.put(Status.MISSING, Color.decode(Config.string("compare.colors.missing")));
        COLORS.put(Status.EXTRA, Color.decode(Config.string("compare.colors.extra")));
        COLORS.put(Status.TYPE, Color.decode(Config.string("compare.colors.type")));
        COLORS.put(Status.VALUE, Color.decode(Config.string("compare.colors.value")));
    }

    static Color colorOf(Status s) {
        return COLORS.get(s);
    }

    /** What the controller needs from its trio (kept narrow on purpose). */
    interface Host {
        RSyntaxTextArea patternArea();
        JTree tree();
        /** The pattern's single parsed entry, or null if it is not valid now. */
        ParsedEntry patternEntry();
        /** Shows the given component in place of the normal source editor (null = restore). */
        void showSource(JComponent replacement);
        /** Shows the given component in place of the normal JSON (null = restore). */
        void showJson(JComponent replacement);
        /** Restores the normal tree after leaving the comparison. */
        void restoreTree();
        void status(String text);
        File proposedJsonFile();
        void setPatternEditable(boolean editable);
    }

    private final Host host;
    private boolean active;
    private boolean showValues = true;

    // ── Sample side ────────────────────────────────────────────────────────
    private final RSyntaxTextArea sampleArea = new RSyntaxTextArea(12, 60) {
        @Override protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getDocument().getLength() > 0) return;
            var g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setColor(Color.GRAY);
            g2.setFont(getFont().deriveFont(Font.ITALIC));
            g2.drawString(Messages.get("compare.sample.placeholder"), getInsets().left + 4,
                    getInsets().top + g2.getFontMetrics().getAscent());
            g2.dispose();
        }
    };
    private final ValidateIcon sampleIcon = new ValidateIcon(16);
    private final JButton btnSampleStatus = new JButton(sampleIcon);
    private final JLabel lblSampleFile = new JLabel(" ");
    private final Timer sampleSync = new Timer(Config.integer("sync.delayMs"), e -> parseSample());
    private ParsedEntry sampleEntry;
    private Path sampleFile;

    // ── Views ──────────────────────────────────────────────────────────────
    private JSplitPane sourceSplit;
    private JComponent originalSource;
    private final JsonEditor mergedEditor = new JsonEditor("");
    private JPanel jsonPanel;
    private final List<Object> highlightTags = new ArrayList<>();
    private final JLabel lblPatternTitle = new JLabel();
    private Node lastDiff;

    CompareController(Host host) {
        this.host = host;
        sampleArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
        sampleArea.setLineWrap(false);
        sampleArea.setHighlightCurrentLine(false);
        sampleArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12));
        sampleSync.setRepeats(false);
        sampleArea.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() {
                sampleArea.repaint();
                sampleIcon.setState(ValidateIcon.State.PENDING);
                btnSampleStatus.repaint();
                sampleSync.restart();
            }
            @Override public void insertUpdate(DocumentEvent e)  { changed(); }
            @Override public void removeUpdate(DocumentEvent e)  { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });
        mergedEditor.setEditable(false);
        mergedEditor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        mergedEditor.setTabSize(4);
        mergedEditor.setPaintTabLines(true);
    }

    boolean isActive() {
        return active;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Enter / leave
    // ─────────────────────────────────────────────────────────────────────

    void enter(JComponent sourceView) {
        if (active) return;
        active = true;
        originalSource = sourceView;
        host.setPatternEditable(false);          // the Pattern is the reference: locked by default

        lblPatternTitle.setText(Messages.get("compare.pattern"));
        var left = titled(lblPatternTitle, sourceView);
        var right = titled(new JLabel(Messages.get("compare.sample")), samplePanel());
        sourceSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right);
        sourceSplit.setResizeWeight(0.5);
        sourceSplit.setContinuousLayout(true);
        sourceSplit.setBorder(null);
        host.showSource(sourceSplit);
        SwingUtilities.invokeLater(() -> sourceSplit.setDividerLocation(0.5));

        host.showJson(jsonPanel());
        recompute();
    }

    void leave() {
        if (!active) return;
        active = false;
        clearHighlights();
        sampleSync.stop();
        host.showSource(null);
        host.showJson(null);
        host.restoreTree();
        host.setPatternEditable(true);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Panels
    // ─────────────────────────────────────────────────────────────────────

    private static JPanel titled(JLabel title, JComponent content) {
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        title.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        var p = new JPanel(new BorderLayout(0, 2));
        p.add(title, BorderLayout.NORTH);
        p.add(content, BorderLayout.CENTER);
        return p;
    }

    private JComponent samplePanel() {
        btnSampleStatus.setFocusable(false);
        btnSampleStatus.addActionListener(e -> { sampleSync.stop(); parseSample(); });
        var btnOpen = new JButton(new FolderIcon(18));
        btnOpen.setToolTipText(Messages.get("compare.sample.open.tip"));
        btnOpen.setFocusable(false);
        btnOpen.addActionListener(e -> openSample());
        var btnCopy = new JButton(new CopyIcon(16));
        btnCopy.setToolTipText(Messages.get("compare.sample.copy.tip"));
        btnCopy.setFocusable(false);
        btnCopy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(sampleArea.getText()), null));
        var tglValues = FindReplacePanel.FlatButton.toggle("~", Messages.get("compare.values.tip"));
        tglValues.setSelected(showValues);
        tglValues.addActionListener(e -> { showValues = tglValues.isSelected(); recompute(); });

        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.add(btnSampleStatus);
        bar.addSeparator();
        bar.add(EditToggle.create(sampleArea, true, Messages.get("source.editable.tip"), null));
        bar.add(btnOpen);
        bar.add(btnCopy);
        bar.add(lblSampleFile);
        bar.add(Box.createHorizontalGlue());
        bar.add(tglValues);

        var scroll = new RTextScrollPane(sampleArea);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(false);
        var p = new JPanel(new BorderLayout());
        p.add(bar, BorderLayout.NORTH);
        p.add(scroll, BorderLayout.CENTER);
        return p;
    }

    private JComponent jsonPanel() {
        if (jsonPanel != null) return jsonPanel;
        var btnSave = new JButton(new SaveIcon(16));
        btnSave.setToolTipText(Messages.get("compare.save.tip"));
        btnSave.setFocusable(false);
        btnSave.addActionListener(e -> {
            var menu = new JPopupMenu();
            var pattern = new JMenuItem(Messages.get("compare.savePattern"));
            pattern.setEnabled(host.patternEntry() != null);
            pattern.addActionListener(ev -> save(host.patternEntry(), "-pattern"));
            var sample = new JMenuItem(Messages.get("compare.saveSample"));
            sample.setEnabled(sampleEntry != null);
            sample.addActionListener(ev -> save(sampleEntry, "-sample"));
            menu.add(pattern);
            menu.add(sample);
            menu.show(btnSave, 0, btnSave.getHeight());
        });
        var btnCopy = new JButton(new CopyIcon(16));
        btnCopy.setToolTipText(Messages.get("compare.copy.tip"));
        btnCopy.setFocusable(false);
        btnCopy.addActionListener(e -> Toolkit.getDefaultToolkit().getSystemClipboard()
                .setContents(new StringSelection(mergedEditor.getText()), null));

        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.add(btnCopy);
        bar.add(btnSave);
        bar.addSeparator();
        for (var s : List.of(Status.MISSING, Status.EXTRA, Status.TYPE, Status.VALUE)) {
            var l = new JLabel(SYMBOLS.get(s) + " " + Messages.get("compare.status." + s.name().toLowerCase()) + "   ");
            l.setForeground(COLORS.get(s));
            bar.add(l);
        }
        var scroll = new RTextScrollPane(mergedEditor);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(true);
        jsonPanel = new JPanel(new BorderLayout(0, JsonViewerPanel.TOOLBAR_GAP));
        jsonPanel.add(bar, BorderLayout.NORTH);
        jsonPanel.add(scroll, BorderLayout.CENTER);
        return jsonPanel;
    }

    // ─────────────────────────────────────────────────────────────────────
    // Sample
    // ─────────────────────────────────────────────────────────────────────

    private void openSample() {
        var chooser = new JFileChooser();
        var dir = host.proposedJsonFile().getParentFile();
        if (dir != null) chooser.setCurrentDirectory(dir);
        chooser.setDialogTitle(Messages.get("compare.sample.open.tip"));
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter(Messages.get("source.open.filter"), "log"));
        if (chooser.showOpenDialog(sampleArea) != JFileChooser.APPROVE_OPTION) return;
        Path path = chooser.getSelectedFile().toPath();
        try {
            String content;
            try {
                content = Files.readString(path, StandardCharsets.UTF_8);
            } catch (MalformedInputException ex) {
                content = Files.readString(path, StandardCharsets.ISO_8859_1);
            }
            setSampleText(content);
            sampleFile = path;
            lblSampleFile.setText(path.getFileName().toString());
            lblSampleFile.setToolTipText(path.toAbsolutePath().toString());
        } catch (IOException ex) {
            ErrorDialog.show(sampleArea, Messages.get("error.readFile", ex.getMessage()), ex);
        }
    }

    /** Sets the Sample text and compares at once (also used by the tests). */
    void setSampleText(String text) {
        sampleArea.setText(text);
        sampleArea.setCaretPosition(0);
        sampleSync.stop();
        parseSample();
    }

    private void parseSample() {
        String text = sampleArea.getText();
        sampleEntry = null;
        if (text.isBlank()) {
            sampleStatus(ValidateIcon.State.NONE, Messages.get("compare.sample.placeholder"));
        } else {
            var valid = LogExtractor.extractAll(text).stream().filter(e -> !e.isError()).toList();
            if (valid.isEmpty()) sampleStatus(ValidateIcon.State.INVALID, Messages.get("compare.sample.none"));
            else if (valid.size() > 1) sampleStatus(ValidateIcon.State.INVALID, Messages.get("compare.sample.several", valid.size()));
            else {
                sampleEntry = valid.get(0);
                sampleStatus(ValidateIcon.State.VALID, Messages.get("compare.sample.ok"));
            }
        }
        recompute();
    }

    private void sampleStatus(ValidateIcon.State state, String tip) {
        sampleIcon.setState(state);
        btnSampleStatus.setToolTipText(tip);
        btnSampleStatus.repaint();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Comparison
    // ─────────────────────────────────────────────────────────────────────

    /** Compares the Pattern and the Sample and updates the tree, the texts and the JSON. */
    void recompute() {
        if (!active) return;
        host.showJson(jsonPanel());              // the Pattern's own sync may have shown its normal JSON
        clearHighlights();
        var pattern = host.patternEntry();
        if (pattern == null || sampleEntry == null) {
            lastDiff = null;
            host.tree().setModel(new DefaultTreeModel(new DefaultMutableTreeNode(Messages.get("compare.waiting"))));
            mergedEditor.setText("");
            mergedEditor.setEmptyMessage(Messages.get("compare.waiting"));
            host.status(Messages.get("compare.waiting"));
            return;
        }
        Node diff = ObjectDiff.compare(pattern.parsed(), sampleEntry.parsed());
        if (!showValues) diff = withoutValueDiffs(diff);
        lastDiff = diff;

        // Tree
        var root = treeNode(diff, null, new ArrayList<>());
        host.tree().setModel(new DefaultTreeModel(root));
        expandDifferences(root);

        // Texts
        highlight(diff, new ArrayList<>(), pattern, sampleEntry);

        // JSON
        var merged = MergedJson.of(diff);
        mergedEditor.setEmptyMessage(null);
        mergedEditor.setText(merged.text());
        mergedEditor.setCaretPosition(0);
        mergedEditor.removeAllLineHighlights();
        for (var m : merged.marks()) {
            var c = translucent(COLORS.get(m.status()));
            for (int line = m.firstLine(); line <= m.lastLine(); line++) {
                try {
                    mergedEditor.addLineHighlight(line, c);
                } catch (javax.swing.text.BadLocationException ignored) { }
            }
        }

        // Summary
        var counts = new EnumMap<Status, Integer>(Status.class);
        count(diff, counts, true);
        int total = counts.values().stream().mapToInt(Integer::intValue).sum();
        host.status(total == 0 ? Messages.get("compare.equal")
                : Messages.get("compare.summary", counts.getOrDefault(Status.MISSING, 0), counts.getOrDefault(Status.EXTRA, 0),
                        counts.getOrDefault(Status.TYPE, 0), counts.getOrDefault(Status.VALUE, 0)));
    }

    /** Value differences hidden (toggle "~" off): they count as equal. */
    private static Node withoutValueDiffs(Node n) {
        var kids = n.children().stream().map(CompareController::withoutValueDiffs).toList();
        var st = n.status() == Status.VALUE ? Status.SAME : n.status();
        return new Node(n.segment(), st, n.inPattern(), n.inSample(), n.pattern(), n.sample(), kids);
    }

    /** Counts top-level differences (a missing/extra subtree counts once). */
    private static void count(Node n, Map<Status, Integer> counts, boolean root) {
        if (!root && n.status() != Status.SAME) {
            counts.merge(n.status(), 1, Integer::sum);
            if (n.status() == Status.MISSING || n.status() == Status.EXTRA) return;
        }
        n.children().forEach(c -> count(c, counts, false));
    }

    private DefaultMutableTreeNode treeNode(Node n, String name, List<Object> path) {
        Object v = n.shown();
        String symbol = SYMBOLS.get(n.status());
        String prefix = symbol.isEmpty() ? "" : symbol + " ";
        String nameSuffix = name == null ? "" : " " + name;
        String label;
        var kind = ObjectDiff.kind(v);
        if (v instanceof DynamicObject o) {
            label = prefix + o.className() + nameSuffix;
        } else if (kind == ObjectDiff.Kind.OBJECT || kind == ObjectDiff.Kind.LIST) {
            label = prefix + (name == null ? "" : name + "  ") + "(" + n.children().size() + ")";
        } else {
            label = prefix + (name == null ? "" : name + "  =  ") + ObjectTreeBuilder.formatLeaf(v);
        }
        if (n.status() == Status.TYPE || n.status() == Status.VALUE) {
            label += "   ← " + describe(n.pattern());
        }
        int inside = n.differencesInside();
        if (inside > 0 && n.status() != Status.MISSING && n.status() != Status.EXTRA) {
            label += "   ◆ " + inside;
        }
        String tooltip = n.status() == Status.SAME
                ? (inside > 0 ? Messages.get("compare.inside", inside) : null)
                : Messages.get("compare.status." + n.status().name().toLowerCase())
                  + "  —  " + Messages.get("compare.tip.values",
                          n.inPattern() ? describe(n.pattern()) : "—", n.inSample() ? describe(n.sample()) : "—");
        var node = new DefaultMutableTreeNode(new DiffTreeValue(label, v, tooltip, n.status(), inside, List.copyOf(path)));
        for (var c : n.children()) {
            var childPath = new ArrayList<>(path);
            childPath.add(c.segment());
            String childName = c.segment() instanceof Integer i ? "[" + i + "]" : String.valueOf(c.segment());
            node.add(treeNode(c, childName, childPath));
        }
        return node;
    }

    private static String describe(Object v) {
        if (v instanceof DynamicObject o) return o.className() + "(…)";
        var k = ObjectDiff.kind(v);
        if (k == ObjectDiff.Kind.OBJECT) return "{…}";
        if (k == ObjectDiff.Kind.LIST) return "[…]";
        return ObjectTreeBuilder.formatLeaf(v);
    }

    /** Expands the path to every difference. */
    private void expandDifferences(DefaultMutableTreeNode root) {
        var tree = host.tree();
        tree.expandRow(0);
        for (var en = root.preorderEnumeration(); en.hasMoreElements(); ) {
            var n = (DefaultMutableTreeNode) en.nextElement();
            if (n.getUserObject() instanceof DiffTreeValue d && d.status() != Status.SAME && n.getParent() != null) {
                tree.expandPath(new TreePath(((DefaultMutableTreeNode) n.getParent()).getPath()));
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────
    // Text highlights
    // ─────────────────────────────────────────────────────────────────────

    private void highlight(Node n, List<Object> path, ParsedEntry pattern, ParsedEntry sample) {
        if (n.status() != Status.SAME && !path.isEmpty()) {
            var c = COLORS.get(n.status());
            boolean inPattern = n.status() != Status.EXTRA, inSample = n.status() != Status.MISSING;
            if (inPattern) mark(host.patternArea(), pattern, path, c);
            if (inSample) mark(sampleArea, sample, path, c);
            if (n.status() == Status.MISSING || n.status() == Status.EXTRA) return;   // the whole subtree is marked
        }
        for (var ch : n.children()) {
            var p = new ArrayList<>(path);
            p.add(ch.segment());
            highlight(ch, p, pattern, sample);
        }
    }

    private void mark(JTextComponent area, ParsedEntry entry, List<Object> path, Color c) {
        var span = entry.spans().stream().filter(s -> s.path().equals(path)).findFirst().orElse(null);
        if (span == null) return;
        try {
            int end = Math.min(span.end(), area.getDocument().getLength());
            var painter = new DefaultHighlighter.DefaultHighlightPainter(translucent(c));
            highlightTags.add(new Object[]{area, area.getHighlighter().addHighlight(span.start(), end, painter)});
        } catch (javax.swing.text.BadLocationException ignored) { }
    }

    private void clearHighlights() {
        for (Object o : highlightTags) {
            var pair = (Object[]) o;
            ((JTextComponent) pair[0]).getHighlighter().removeHighlight(pair[1]);
        }
        highlightTags.clear();
        mergedEditor.removeAllLineHighlights();
    }

    private static Color translucent(Color c) {
        return new Color(c.getRed(), c.getGreen(), c.getBlue(), HIGHLIGHT_ALPHA);
    }

    // ─────────────────────────────────────────────────────────────────────
    // Node actions in comparison mode
    // ─────────────────────────────────────────────────────────────────────

    /** "Locate": selects the property in the Pattern and in the Sample (where it exists). */
    List<HoverActionMenu.Item> actionsFor(DefaultMutableTreeNode node) {
        if (!(node.getUserObject() instanceof DiffTreeValue d) || d.path().isEmpty()) return List.of();
        return List.of(new HoverActionMenu.Item(Messages.get("menu.locate"), HoverActionMenu.ICON_LOCATE, true,
                Messages.get("compare.locate.tip"), () -> locate(d.path())));
    }

    void locate(List<Object> path) {
        select(host.patternArea(), host.patternEntry(), path);
        select(sampleArea, sampleEntry, path);
        int off = JsonPath.offsetOf(mergedEditor.getText(), path);
        if (off >= 0) {
            mergedEditor.select(off, off + 1);
            SwingUtilities.invokeLater(() -> Centering.on(mergedEditor, off));
        }
        host.status(Messages.get("status.located", JsonPath.format(path)));
    }

    private static void select(JTextComponent area, ParsedEntry entry, List<Object> path) {
        if (entry == null) return;
        var span = entry.spans().stream().filter(s -> s.path().equals(path)).findFirst().orElse(null);
        if (span == null) return;
        int end = Math.min(span.end(), area.getDocument().getLength());
        area.select(span.start(), end);
        area.getCaret().setSelectionVisible(true);
        Centering.on(area, span.start());
    }

    // ─────────────────────────────────────────────────────────────────────
    // Save
    // ─────────────────────────────────────────────────────────────────────

    private void save(ParsedEntry entry, String suffix) {
        if (entry == null) return;
        try {
            String json = JsonSerializer.toPrettyJson(entry.parsed());
            File base = suffix.equals("-sample") && sampleFile != null
                    ? new File(host.proposedJsonFile().getParentFile(), sampleFile.getFileName().toString())
                    : host.proposedJsonFile();
            String name = base.getName();
            int dot = name.lastIndexOf('.');
            String ext = Config.string("json.extension");
            File proposal = new File(base.getParentFile(), (dot > 0 ? name.substring(0, dot) : name) + suffix + ext);
            JsonViewerPanel.saveToFile(sampleArea, Messages.get(suffix.equals("-sample") ? "compare.saveSample" : "compare.savePattern"),
                    json, proposal, host::status);
        } catch (IOException ex) {
            ErrorDialog.show(sampleArea, Messages.get("json.save.error"), ex);
        }
    }

    /** The current comparison (for the tests). */
    Node lastDiff() {
        return lastDiff;
    }

    RSyntaxTextArea sampleArea() {
        return sampleArea;
    }

    JsonEditor mergedEditor() {
        return mergedEditor;
    }
}
