package com.dynamicparser;

import com.dynamicparser.ui.DialogoErrores;
import com.dynamicparser.ui.MainFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    public static void main(String[] args) {
        // Excepciones no controladas: diálogo con la traza (además de la consola)
        DialogoErrores.instalar();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new MainFrame().setVisible(true);
        });
    }
}
