package com.dynamicparser.ui;

import com.dynamicparser.ui.fold.FoldGutter;
import com.dynamicparser.ui.fold.JsonEditor;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.util.List;
import java.util.function.Consumer;

/**
 * Panel de una pestaña JSON: editor (RSyntaxTextArea, subclase de JTextArea)
 * con números de línea + barra con "Editable", "Validar" y "Copiar".
 *
 * Margen de plegado propio (FoldGutter) con íconos dibujados ⌄ ⌃ » «,
 * dos espacios por línea, a la derecha de los números de línea.
 */
public class JsonViewerPanel extends JPanel {

    private final JsonEditor editor;

    /** Propone el archivo destino al guardar (lo provee quien crea el panel). */
    private java.util.function.Supplier<java.io.File> propuestaArchivo =
            () -> new java.io.File(com.dynamicparser.Config.texto("json.nombrePorDefecto"));

    /** true si el usuario editó el JSON a mano (entonces no se sobrescribe al sincronizar). */
    private boolean modificado;
    /** true mientras el texto se pone por código (no cuenta como edición del usuario). */
    private boolean cargando;
    private final JLabel lblInfo = new JLabel(" ");

    public JsonViewerPanel(String json) {
        this(json, null);
    }

    /**
     * @param ubicador acción "Ubicar en el árbol" que recibe la ruta JSON
     *                 (segmentos String = clave, Integer = índice), o null.
     */
    public JsonViewerPanel(String json, Consumer<List<Object>> ubicador) {
        super(new BorderLayout(0, 4));

        editor = new JsonEditor(json);
        editor.setUbicador(ubicador);
        editor.setAntiAliasingEnabled(true);
        editor.setTabSize(4);
        // Guías verticales punteadas de sangría (como Postman), cada 4 espacios
        editor.setPaintTabLines(true);
        editor.setTabLineColor(new Color(200, 200, 200));
        editor.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 13));
        editor.setEditable(false);
        editor.setCaretPosition(0);
        editor.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void cambio() { if (!cargando) modificado = true; }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e)  { cambio(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e)  { cambio(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { }
        });

        var scroll = new RTextScrollPane(editor);
        scroll.setLineNumbersEnabled(true);
        scroll.setFoldIndicatorEnabled(false);   // reemplazado por FoldGutter

        // Row header = [números de línea | margen de plegado propio]
        var gutter = scroll.getGutter();
        // Sin línea horizontal bajo los rangos colapsados (Postman no la dibuja)
        // (con null RSTA usa su gris por defecto; se usa el color de fondo del editor)
        gutter.setFoldIndicatorForeground(editor.getBackground());
        var foldGutter = new FoldGutter(editor);
        foldGutter.setBackground(gutter.getBackground());
        var cabecera = new JPanel(new BorderLayout());
        cabecera.setBackground(gutter.getBackground());
        cabecera.add(gutter, BorderLayout.CENTER);
        cabecera.add(foldGutter, BorderLayout.EAST);
        scroll.setRowHeaderView(cabecera);

        var chkEditable = new JCheckBox("Editable");
        chkEditable.addActionListener(e -> {
            editor.setEditable(chkEditable.isSelected());
            lblInfo.setText(chkEditable.isSelected() ? "Modo edición" : " ");
        });

        var btnValidar = new JButton("Validar");
        btnValidar.addActionListener(e -> validar());

        var btnCopiar = new JButton("📋 Copiar");
        btnCopiar.addActionListener(e -> copiar());

        // Buscar/Reemplazar (Ctrl+F / Ctrl+H); reemplazar sólo en modo Editable
        var br = new BuscarReemplazar(editor);
        if (ubicador != null) {
            br.agregarAccion("→ Árbol", "Ubicar en el árbol la propiedad de la coincidencia/cursor", () -> {
                var ruta = com.dynamicparser.ui.fold.JsonPath.pathAt(editor.getText(), editor.getSelectionStart());
                if (ruta != null) ubicador.accept(ruta);
                else lblInfo.setText("⚠ El cursor no está sobre una clave o valor");
            });
        }

        // Una sola barra: [Editable] | [Validar] [Copiar] | [›] 🔍 [buscar ▾ (se estira)] Aa ab .* n de m ↑ ↓ | info
        var barra = new JToolBar();
        barra.setFloatable(false);
        barra.setRollover(true);
        barra.add(chkEditable);
        barra.addSeparator();
        barra.add(btnValidar);
        barra.add(btnCopiar);

        var btnGuardar = new JButton("Guardar…", new IconoGuardar(16));
        btnGuardar.setToolTipText("Guardar el JSON en un archivo .json (se valida antes de guardar)  (Ctrl+S)");
        btnGuardar.addActionListener(e -> guardar());
        barra.add(btnGuardar);
        editor.getInputMap().put(KeyStroke.getKeyStroke(java.awt.event.KeyEvent.VK_S,
                java.awt.event.InputEvent.CTRL_DOWN_MASK), "json.guardar");
        editor.getActionMap().put("json.guardar", new AbstractAction() {
            @Override public void actionPerformed(java.awt.event.ActionEvent e) { guardar(); }
        });
        barra.addSeparator();
        barra.add(br.getPanel());
        barra.addSeparator();
        barra.add(lblInfo);

        // "Wrap Lines" fijo en el borde derecho (como Postman), siempre visible aunque la barra no quepa
        var tglWrap = new JToggleButton(new IconoWrap());
        tglWrap.setToolTipText("Wrap Lines — ajustar líneas largas");
        tglWrap.setFocusable(false);
        tglWrap.addActionListener(e -> editor.setLineWrap(tglWrap.isSelected()));
        var barraDerecha = new JToolBar();
        barraDerecha.setFloatable(false);
        barraDerecha.setRollover(true);
        barraDerecha.add(tglWrap);

        var barras = new JPanel(new BorderLayout());
        barras.add(barra, BorderLayout.CENTER);
        barras.add(barraDerecha, BorderLayout.EAST);

        add(barras, BorderLayout.NORTH);
        add(scroll, BorderLayout.CENTER);
    }

    /**
     * Ubica en el JSON la clave/elemento de la ruta: expande el rango que la
     * contenga, la selecciona y la centra en pantalla. false si no existe.
     */
    public boolean ubicarRuta(List<Object> ruta) {
        String texto = editor.getText();
        int off = com.dynamicparser.ui.fold.JsonPath.offsetOf(texto, ruta);
        if (off < 0) {
            lblInfo.setText("⚠ No está en este JSON: " + com.dynamicparser.ui.fold.JsonPath.format(ruta));
            return false;
        }
        // Seleccionar la clave completa ("clave") o hasta el fin de la línea
        int fin = off + 1;
        if (texto.charAt(off) == '"') {
            int q = texto.indexOf('"', off + 1);
            while (q > 0 && texto.charAt(q - 1) == '\\') q = texto.indexOf('"', q + 1);
            fin = q > 0 ? q + 1 : off + 1;
        } else {
            int eol = texto.indexOf('\n', off);
            fin = eol < 0 ? texto.length() : eol;
            while (fin > off + 1 && (texto.charAt(fin - 1) == ',' || Character.isWhitespace(texto.charAt(fin - 1)))) fin--;
        }
        editor.getFoldManager().ensureOffsetNotInClosedFold(off);
        editor.select(off, fin);
        editor.requestFocusInWindow();
        SwingUtilities.invokeLater(() -> Centrar.en(editor, off));
        lblInfo.setText(ruta.isEmpty() ? "Raíz" : "→ " + com.dynamicparser.ui.fold.JsonPath.format(ruta));
        return true;
    }

    /** Formato del tooltip de ruta (p. ej. con marcas "<,>" en las claves Map). */
    public void setFormateadorTooltip(java.util.function.Function<List<Object>, String> f) {
        editor.setFormateadorTooltip(f);
    }

    /**
     * Deja el panel visible pero inactivo (barra y editor deshabilitados), con un
     * mensaje: se usa como marcador cuando todavía no hay ninguna serialización.
     */
    public void deshabilitar(String mensaje) {
        deshabilitarRec(this);
        editor.setEnabled(false);
        editor.setHighlightCurrentLine(false);
        lblInfo.setEnabled(true);
        lblInfo.setText("ℹ " + mensaje);
    }

    private static void deshabilitarRec(Container c) {
        for (Component k : c.getComponents()) {
            k.setEnabled(false);
            if (k instanceof Container cc) deshabilitarRec(cc);
        }
    }

    /** ¿El usuario editó este JSON a mano? */
    public boolean isModificado() {
        return modificado;
    }

    /**
     * Reemplaza el JSON (sincronización con el texto de origen), conservando la
     * posición del cursor y del scroll. No hace nada si el contenido es igual.
     */
    public void actualizarJson(String json) {
        if (json.equals(editor.getText())) {
            lblInfo.setText(" ");
            return;
        }
        int caret = editor.getCaretPosition();
        var vista = editor.getVisibleRect();
        cargando = true;
        try {
            editor.setText(json);
        } finally {
            cargando = false;
        }
        modificado = false;
        editor.setCaretPosition(Math.min(caret, json.length()));
        SwingUtilities.invokeLater(() -> editor.scrollRectToVisible(vista));
        lblInfo.setText("↻ Actualizado desde el texto de origen");
    }

    /** Indica que la pestaña ya no refleja el texto de origen (y por qué). */
    public void marcarDesactualizado(String motivo) {
        lblInfo.setText("⚠ " + motivo);
    }

    /** Texto completo (incluye lo colapsado: el plegado sólo oculta vistas). */
    public String getJson() {
        return editor.getText();
    }

    private void copiar() {
        Toolkit.getDefaultToolkit().getSystemClipboard()
               .setContents(new StringSelection(getJson()), null);
        lblInfo.setText("✔ JSON copiado al portapapeles");
    }

    public void setPropuestaArchivo(java.util.function.Supplier<java.io.File> propuesta) {
        if (propuesta != null) this.propuestaArchivo = propuesta;
    }

    /** Valida el JSON y lo guarda en el archivo que elija el usuario (propuesta por defecto). */
    private void guardar() {
        String json = getJson();
        String error = JsonSerializer.validate(json);
        if (error != null) {
            lblInfo.setText("⚠ " + error);
            int r = JOptionPane.showConfirmDialog(this,
                    "El JSON no es válido:\n" + error + "\n\n¿Guardarlo de todos modos?",
                    "JSON inválido", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        var chooser = new JFileChooser();
        chooser.setDialogTitle("Guardar JSON");
        chooser.setFileFilter(new javax.swing.filechooser.FileNameExtensionFilter("JSON (*.json)", "json"));
        var propuesta = propuestaArchivo.get();
        if (propuesta.getParentFile() != null) chooser.setCurrentDirectory(propuesta.getParentFile());
        chooser.setSelectedFile(propuesta);
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        var destino = chooser.getSelectedFile();
        String ext = com.dynamicparser.Config.texto("json.extension");
        if (!destino.getName().toLowerCase().endsWith(ext.toLowerCase())) {
            destino = new java.io.File(destino.getParentFile(), destino.getName() + ext);
        }
        if (destino.exists()) {
            int r = JOptionPane.showConfirmDialog(this, "«" + destino.getName() + "» ya existe. ¿Reemplazarlo?",
                    "Guardar JSON", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (r != JOptionPane.YES_OPTION) return;
        }
        try {
            java.nio.file.Files.writeString(destino.toPath(), json, java.nio.charset.StandardCharsets.UTF_8);
            lblInfo.setText("✔ Guardado: " + destino.getName() + (error == null ? "  (JSON válido)" : "  (⚠ inválido)"));
            lblInfo.setToolTipText(destino.getAbsolutePath());
        } catch (java.io.IOException ex) {
            DialogoErrores.mostrar(this, "No se pudo guardar el JSON", ex);
        }
    }

    private void validar() {
        String error = JsonSerializer.validate(getJson());
        lblInfo.setText(error == null ? "✔ JSON válido" : "⚠ " + error);
    }
}
