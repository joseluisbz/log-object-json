package com.logobjectjson.ui;

import javax.swing.*;
import javax.swing.text.JTextComponent;
import java.util.function.Consumer;

/**
 * "✎" toggle that allows/forbids editing a text editor (same button in the
 * source text and the JSON toolbars). Turning editing on moves the focus to
 * the editor and shows its blinking caret.
 */
public final class EditToggle {

    private EditToggle() { }

    /**
     * @param editor     the editor to make editable or read-only
     * @param initially  whether editing starts allowed
     * @param tooltip    button tooltip
     * @param onChange   called after each change with the new state (may be null)
     */
    public static JToggleButton create(JTextComponent editor, boolean initially, String tooltip,
                                       Consumer<Boolean> onChange) {
        var toggle = new JToggleButton(new EditIcon(16), initially);
        toggle.setToolTipText(tooltip);
        toggle.setFocusable(false);
        editor.setEditable(initially);
        toggle.addActionListener(e -> {
            boolean on = toggle.isSelected();
            editor.setEditable(on);
            if (on) {
                editor.requestFocusInWindow();
                editor.getCaret().setVisible(true);   // the caret only blinks once the editor has the focus
            }
            if (onChange != null) onChange.accept(on);
        });
        return toggle;
    }
}
