package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import com.logobjectjson.ui.fold.FoldGutter;
import com.logobjectjson.ui.fold.JsonEditor;
import com.logobjectjson.ui.fold.JsonPath;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.ActionEvent;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * JSON panel: editor (RSyntaxTextArea, a JTextArea subclass) with line
 * numbers + a toolbar with "Editable", "Validate", "Copy", "Save" and search.
 *
 * Own fold gutter (FoldGutter) with drawn icons ⌄ ⌃ » «, two slots per line,
 * to the right of the line numbers.
 */
public class JsonViewerPanel extends JPanel {

    private static final Color TAB_LINE_COLOR = new Color(200, 200, 200);
    /** Vertical gap between the toolbar and the content (neighbor panels use the same one to align). */
    public static final int TOOLBAR_GAP = 4;

    private final JsonEditor editor;

    /** Proposes the target file when saving (provided by whoever creates the panel). */
    private Supplier<File> proposedFile = () -> new File(Config.string("json.defaultName"));

    /** true if the user edited the JSON by hand (then it is not overwritten on sync). */
    private boolean modified;
    /** true while the text is set from code (does not count as a user edit). */
    private boolean loading;
    private final JLabel lblInfo = new JLabel(" ");
    /** Toolbars row (main toolbar + Wrap). */
    private JPanel bars;

    public JsonViewerPanel(String json) {
        this(json, null);
    }

    /**
     * @param locator "Locate in tree" action receiving the JSON path
     *                (String segments = key, Integer = index), or null.
     */
    public JsonViewerPanel(String json, Consumer<List<Object>> locator) {
        super(new BorderLayout(0, TOOLBAR_GAP));

        editor = new JsonEditor(json);
        editor.setLocator(locator);
        editor.setAntiAliasingEnabled(true);
        editor.setTabSize(4);
        // Dotted vertical indent guides (like Postman), every 4 spaces
        editor.setPaintTabLines(true);
        editor.setTabLineColor(TAB_LINE_COLOR);
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setEditable(false);
        editor.setCaretPosition(0);
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void changed() { if (!loading) modified = true; }
            @Override public void insertUpdate(DocumentEvent e)  { changed(); }
            @Override public void removeUpdate(DocumentEvent e)  { changed(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        var scroll = new RTextScrollPane(editor);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(false);   // replaced by FoldGutter

        // Row header = [line numbers | own fold gutter]
        var gutter = scroll.getGutter();
        // No horizontal line under collapsed ranges (Postman does not draw it)
        // (with null RSTA uses its default gray; the editor background is used instead)
        gutter.setFoldIndicatorForeground(editor.getBackground());
        var foldGutter = new FoldGutter(editor);
        foldGutter.setBackground(gutter.getBackground());
        var rowHeader = new JPanel(new BorderLayout());
        rowHeader.setBackground(gutter.getBackground());
        rowHeader.add(gutter, BorderLayout.CENTER);
        rowHeader.add(foldGutter, BorderLayout.EAST);
        scroll.setRowHeaderView(rowHeader);

        var chkEditable = new JCheckBox(Messages.get("json.editable"));
        chkEditable.addActionListener(e -> {
            editor.setEditable(chkEditable.isSelected());
            lblInfo.setText(chkEditable.isSelected() ? Messages.get("json.editMode") : " ");
        });

        var btnValidate = new JButton(Messages.get("json.validate"));
        btnValidate.addActionListener(e -> validateJson());

        var btnCopy = new JButton(new CopyIcon(16));
        btnCopy.setToolTipText(Messages.get("json.copy.tip"));
        btnCopy.addActionListener(e -> copy());

        // Find/Replace (Ctrl+F / Ctrl+H); replace only in Editable mode
        var findReplace = new FindReplacePanel(editor);

        // One toolbar: [Editable] | [Validate] [Copy] [Save] | search | info
        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.add(chkEditable);
        bar.addSeparator();
        bar.add(btnValidate);
        bar.add(btnCopy);

        var btnSave = new JButton(new SaveIcon(16));
        btnSave.setToolTipText(Messages.get("json.save.tip"));
        btnSave.addActionListener(e -> save());
        bar.add(btnSave);
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "json.save");
        editor.getActionMap().put("json.save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { save(); }
        });
        bar.addSeparator();
        bar.add(findReplace.getPanel());
        bar.addSeparator();
        bar.add(lblInfo);

        // "Wrap Lines" fixed on the right edge (like Postman), always visible even if the toolbar does not fit
        var tglWrap = new JToggleButton(new WrapIcon());
        tglWrap.setToolTipText(Messages.get("wrap.tip"));
        tglWrap.setFocusable(false);
        tglWrap.addActionListener(e -> editor.setLineWrap(tglWrap.isSelected()));
        var rightBar = new JToolBar();
        rightBar.setFloatable(false);
        rightBar.setRollover(true);
        rightBar.add(tglWrap);

        bars = new JPanel(new BorderLayout());
        bars.add(bar, BorderLayout.CENTER);
        bars.add(rightBar, BorderLayout.EAST);

        add(bars, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Locates the path's key/element in the JSON: expands the fold containing
     * it, selects it and centers it on screen. false if it does not exist.
     */
    public boolean locatePath(List<Object> path) {
        String text = editor.getText();
        int off = JsonPath.offsetOf(text, path);
        if (off < 0) {
            lblInfo.setText(Messages.get("json.pathNotFound", JsonPath.format(path)));
            return false;
        }
        // Select the whole key ("key") or up to the end of the line
        int end;
        if (text.charAt(off) == '"') {
            int q = text.indexOf('"', off + 1);
            while (q > 0 && text.charAt(q - 1) == '\\') q = text.indexOf('"', q + 1);
            end = q > 0 ? q + 1 : off + 1;
        } else {
            int eol = text.indexOf('\n', off);
            end = eol < 0 ? text.length() : eol;
            while (end > off + 1 && (text.charAt(end - 1) == ',' || Character.isWhitespace(text.charAt(end - 1)))) end--;
        }
        editor.getFoldManager().ensureOffsetNotInClosedFold(off);
        editor.select(off, end);
        editor.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> Centering.on(editor, off));
        lblInfo.setText(path.isEmpty() ? Messages.get("json.root") : "→ " + JsonPath.format(path));
        return true;
    }

    /** Height of the toolbars row (to align neighboring toolbars with it). */
    public int toolbarHeight() {
        return bars.getPreferredSize().height;
    }

    /** Path tooltip format (e.g. with "<,>" marks on Map keys). */
    public void setTooltipFormatter(Function<List<Object>, String> f) {
        editor.setTooltipFormatter(f);
    }

    /**
     * Leaves the panel visible but inactive (toolbar and editor disabled), with a
     * message: used as a placeholder while there is no serialization yet.
     */
    public void showDisabled(String message) {
        disableAll(this);
        editor.setEnabled(false);
        editor.setHighlightCurrentLine(false);
        lblInfo.setEnabled(true);
        lblInfo.setText("ℹ " + message);
    }

    private static void disableAll(Container c) {
        for (Component k : c.getComponents()) {
            k.setEnabled(false);
            if (k instanceof Container cc) disableAll(cc);
        }
    }

    /** Did the user edit this JSON by hand? */
    public boolean isModified() {
        return modified;
    }

    /**
     * Replaces the JSON (sync with the source text), keeping the caret and
     * scroll position. Does nothing if the content is the same.
     */
    public void updateJson(String json) {
        if (json.equals(editor.getText())) {
            lblInfo.setText(" ");
            return;
        }
        int caret = editor.getCaretPosition();
        var view = editor.getVisibleRect();
        loading = true;
        try {
            editor.setText(json);
        } finally {
            loading = false;
        }
        modified = false;
        editor.setCaretPosition(Math.min(caret, json.length()));
        SwingUtilities.invokeLater(() -> editor.scrollRectToVisible(view));
        lblInfo.setText(Messages.get("json.updated"));
    }

    /** Marks that the panel no longer reflects the source text (and why). */
    public void markOutdated(String reason) {
        lblInfo.setText("⚠ " + reason);
    }

    /** Full text (includes collapsed parts: folding only hides views). */
    public String getJson() {
        return editor.getText();
    }

    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(getJson()), null);
        lblInfo.setText(Messages.get("json.copied"));
    }

    public void setProposedFile(Supplier<File> proposal) {
        if (proposal != null) this.proposedFile = proposal;
    }

    /** Validates the JSON and saves it to the file the user picks (default proposal). */
    private void save() {
        String json = getJson();
        String error = JsonSerializer.validate(json);
        if (error != null) {
            lblInfo.setText("⚠ " + error);
            int r = JOptionPane.showConfirmDialog(this, Messages.get("json.invalid.confirm", error),
                    Messages.get("json.invalid.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        var chooser = new JFileChooser();
        chooser.setDialogTitle(Messages.get("json.save.title"));
        chooser.setFileFilter(new FileNameExtensionFilter(Messages.get("json.save.filter"), "json"));
        var proposal = proposedFile.get();
        if (proposal.getParentFile() != null) chooser.setCurrentDirectory(proposal.getParentFile());
        chooser.setSelectedFile(proposal);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        var target = chooser.getSelectedFile();
        String ext = Config.string("json.extension");
        if (!target.getName().toLowerCase().endsWith(ext.toLowerCase())) {
            target = new File(target.getParentFile(), target.getName() + ext);
        }
        if (target.exists()) {
            int r = JOptionPane.showConfirmDialog(this, Messages.get("json.save.exists", target.getName()),
                    Messages.get("json.save.title"), JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try {
            Files.writeString(target.toPath(), json, StandardCharsets.UTF_8);
            lblInfo.setText(Messages.get(error == null ? "json.saved.valid" : "json.saved.invalid", target.getName()));
            lblInfo.setToolTipText(target.getAbsolutePath());
        } catch (IOException ex) {
            ErrorDialog.show(this, Messages.get("json.save.error"), ex);
        }
    }

    private void validateJson() {
        String error = JsonSerializer.validate(getJson());
        lblInfo.setText(error == null ? Messages.get("json.valid") : "⚠ " + error);
    }
}
