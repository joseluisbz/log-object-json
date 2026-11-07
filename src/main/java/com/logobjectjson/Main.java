package com.logobjectjson;

import com.logobjectjson.ui.ErrorDialog;
import com.logobjectjson.ui.MainFrame;
import javax.swing.SwingUtilities;
import javax.swing.UIManager;

public class Main {
    public static void main(String[] args) {
        // Uncaught exceptions: dialog with the stack trace (besides the console)
        ErrorDialog.install();
        SwingUtilities.invokeLater(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            } catch (Exception ignored) {}
            new MainFrame().setVisible(true);
        });
    }
}
