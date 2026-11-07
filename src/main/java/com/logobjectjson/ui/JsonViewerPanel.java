package com.logobjectjson.ui;

import com.logobjectjson.Config;
import com.logobjectjson.Messages;
import com.logobjectjson.ui.fold.FoldGutter;
import com.logobjectjson.ui.fold.JsonEditor;
import com.logobjectjson.ui.fold.JsonPath;
import com.logobjectjson.validation.JsonLinter;
import com.logobjectjson.validation.Problem;
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
import java.util.ArrayList;
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
    /** Where status messages go (the trio's status bar); ignored until set. */
    private Consumer<String> statusSink = s -> { };
    private final ValidateIcon validateIcon = new ValidateIcon(16);
    private JButton btnValidate;
    private JButton btnSave;
    /** Problems list, squiggles, error strip and gutter marks of the JSON. */
    private ProblemsView problemsView;
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

        // Editable: pencil toggle (highlighted while editing is allowed)
        var tglEditable = EditToggle.create(editor, false, Messages.get("json.editable.tip"),
                on -> info(on ? Messages.get("json.editMode") : " "));

        // Validate: validated live while editing (problems view); a click validates now
        // and, if there are errors, goes to the first one
        btnValidate = new JButton(validateIcon);
        btnValidate.setToolTipText(Messages.get("json.validate.tip"));
        btnValidate.setFocusable(false);
        btnValidate.addActionListener(e -> validateNow());
        editor.getDocument().addDocumentListener(new DocumentListener() {
            private void edited() {
                if (loading) return;
                validateIcon.setState(ValidateIcon.State.PENDING);
                btnValidate.repaint();
            }
            @Override public void insertUpdate(DocumentEvent e)  { edited(); }
            @Override public void removeUpdate(DocumentEvent e)  { edited(); }
            @Override public void changedUpdate(DocumentEvent e) { }
        });

        var btnCopy = new JButton(new CopyIcon(16));
        btnCopy.setToolTipText(Messages.get("json.copy.tip"));
        btnCopy.addActionListener(e -> copy());

        // Find/Replace (Ctrl+F / Ctrl+H); replace only in Editable mode
        var findReplace = new FindReplacePanel(editor);

        // One toolbar: [✎] | [✔] [Copy] [Save] | search | info
        var bar = new JToolBar();
        bar.setFloatable(false);
        bar.setRollover(true);
        bar.add(tglEditable);
        bar.addSeparator();
        bar.add(btnValidate);
        bar.add(btnCopy);

        btnSave = new JButton(new SaveIcon(16));
        btnSave.setToolTipText(Messages.get("json.save.tip"));
        btnSave.addActionListener(e -> save());
        bar.add(btnSave);
        editor.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK), "json.save");
        editor.getActionMap().put("json.save", new AbstractAction() {
            @Override public void actionPerformed(ActionEvent e) { save(); }
        });
        bar.addSeparator();
        bar.add(findReplace.getPanel());

        bars = new JPanel(new BorderLayout());
        bars.add(bar, BorderLayout.CENTER);

        add(bars, BorderLayout.NORTH);
        problemsView = new ProblemsView(editor, scroll, this::analyze, this::problemsChanged);
        add(problemsView, BorderLayout.CENTER);
        markValid();                 // generated from the tree: valid by construction
    }

    /**
     * Locates the path's key/element in the JSON: expands the fold containing
     * it, selects it and centers it on screen. false if it does not exist.
     */
    public boolean locatePath(List<Object> path) {
        String text = editor.getText();
        int off = JsonPath.offsetOf(text, path);
        if (off < 0) {
            info(Messages.get("json.pathNotFound", JsonPath.format(path)));
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
        info(path.isEmpty() ? Messages.get("json.root") : "→ " + JsonPath.format(path));
        return true;
    }

    /** Height of the toolbars row (to align neighboring toolbars with it). */
    public int toolbarHeight() {
        return bars.getPreferredSize().height;
    }

    /** Where the status messages of this panel go (e.g. the trio's status bar). */
    public void setStatusSink(Consumer<String> sink) {
        this.statusSink = sink != null ? sink : s -> { };
    }

    private void info(String message) {
        statusSink.accept(message);
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
        editor.setEmptyMessage("ℹ " + message);
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
        markValid();
        editor.setCaretPosition(Math.min(caret, json.length()));
        SwingUtilities.invokeLater(() -> editor.scrollRectToVisible(view));
        info(Messages.get("json.updated"));
    }

    /** JSON generated from the tree is valid by construction: no need to re-parse it. */
    private void markValid() {
        validateIcon.setState(ValidateIcon.State.VALID);
        btnSave.setEnabled(true);
        btnValidate.setToolTipText(Messages.get("json.validate.tip"));
        btnValidate.repaint();
    }

    /** Marks that the panel no longer reflects the source text (and why). */
    public void markOutdated(String reason) {
        info("⚠ " + reason);
    }

    /** Full text (includes collapsed parts: folding only hides views). */
    public String getJson() {
        return editor.getText();
    }

    private void copy() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(getJson()), null);
        info(Messages.get("json.copied"));
    }

    public void setProposedFile(Supplier<File> proposal) {
        if (proposal != null) this.proposedFile = proposal;
    }

    /** Validates the JSON and saves it to the file the user picks (default proposal). */
    private void save() {
        String json = getJson();
        if (JsonSerializer.validate(json) != null) {   // Save is disabled while invalid; Ctrl+S too
            validateNow();
            return;
        }
        saveToFile(this, Messages.get("json.save.title"), json, proposedFile.get(), this::info);
    }

    /**
     * Asks for a .json file (proposing one), confirms overwriting and writes the JSON.
     * Shared by this panel and the comparison (Save Pattern / Save Sample).
     */
    static void saveToFile(Component parent, String title, String json, File proposal, Consumer<String> info) {
        var chooser = new JFileChooser();
        chooser.setDialogTitle(title);
        chooser.setFileFilter(new FileNameExtensionFilter(Messages.get("json.save.filter"), "json"));
        if (proposal.getParentFile() != null) chooser.setCurrentDirectory(proposal.getParentFile());
        chooser.setSelectedFile(proposal);
        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) return;

        var target = chooser.getSelectedFile();
        String ext = Config.string("json.extension");
        if (!target.getName().toLowerCase().endsWith(ext.toLowerCase())) {
            target = new File(target.getParentFile(), target.getName() + ext);
        }
        if (target.exists()) {
            int r = JOptionPane.showConfirmDialog(parent, Messages.get("json.save.exists", target.getName()),
                    title, JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try {
            Files.writeString(target.toPath(), json, StandardCharsets.UTF_8);
            info.accept(Messages.get("json.saved.valid", target.getAbsolutePath()));
        } catch (IOException ex) {
            ErrorDialog.show(parent, Messages.get("json.save.error"), ex);
        }
    }

    /** Validates now (explicit click on ✔/✖): shows the problems and goes to the first error. */
    private void validateNow() {
        var ps = problemsView.validateNow();
        if (ps.stream().anyMatch(Problem::isError)) problemsView.goToFirst();
    }

    /**
     * Problems of the JSON text: the tolerant linter lists every problem it can find;
     * Jackson has the final word on validity (so the linter never makes a valid JSON
     * invalid, and an error the linter misses is still reported).
     */
    private List<Problem> analyze(String json) {
        if (!modified) return List.of();          // generated from the tree: valid by construction
        var found = new ArrayList<>(JsonLinter.lint(json));
        String jackson = JsonSerializer.validate(json);
        boolean linterErrors = found.stream().anyMatch(Problem::isError);
        if (jackson == null && linterErrors) {
            found.removeIf(Problem::isError);
        } else if (jackson != null && !linterErrors) {
            found.add(new Problem(JsonSerializer.errorOffset(json), 1, Problem.Severity.ERROR,
                    Messages.get("lint.other", jackson)));
        }
        return found;
    }

    /** New validation result: ✔/✖, Save only when valid, summary in the info label. */
    private void problemsChanged(List<Problem> problems) {
        long errors = problems.stream().filter(Problem::isError).count();
        validateIcon.setState(errors == 0 ? ValidateIcon.State.VALID : ValidateIcon.State.INVALID);
        btnSave.setEnabled(errors == 0);
        btnValidate.setToolTipText(errors == 0 ? Messages.get("json.validate.tip") : Messages.get("json.invalid.goTo", errors));
        btnValidate.repaint();
        if (errors > 0) info(Messages.get("json.invalid.saveDisabled", errors));
        else if (modified) info(Messages.get("json.valid"));
    }
}
