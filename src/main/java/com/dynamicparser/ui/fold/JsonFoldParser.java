package com.dynamicparser.ui.fold;

import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.folding.Fold;
import org.fife.ui.rsyntaxtextarea.folding.FoldParser;
import org.fife.ui.rsyntaxtextarea.folding.FoldType;

import javax.swing.text.BadLocationException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Analizador de rangos plegables estilo Postman para JSON.
 *
 * Reglas:
 *  • Empareja { } y [ ] ignorando los que están dentro de strings (y escapes \").
 *  • Un rango abre en la línea A y cierra en la línea C; es plegable sólo si C − A ≥ 2.
 *  • El Fold de RSTA termina al final de la línea C−1: al colapsar se ocultan
 *    A+1 … C−1 y las líneas A y C permanecen visibles (como Postman).
 *  • Cierres que no encajan con la apertura pendiente se ignoran (JSON editado/mal formado).
 */
public class JsonFoldParser implements FoldParser {

    /** Rango emparejado (offsets de las llaves y sus líneas). */
    private record Range(int openOffset, int closeOffset, int openLine, int closeLine) { }

    @Override
    public List<Fold> getFolds(RSyntaxTextArea textArea) {
        var folds = new ArrayList<Fold>();
        try {
            List<Range> ranges = findRanges(textArea);
            ranges.sort(Comparator.comparingInt(Range::openOffset));

            // Construir el árbol de Folds respetando el anidamiento
            var stack = new ArrayDeque<Object[]>();   // {Range, Fold}
            for (Range r : ranges) {
                while (!stack.isEmpty() && ((Range) stack.peek()[0]).closeOffset() < r.openOffset()) {
                    stack.pop();
                }
                Fold fold = stack.isEmpty()
                        ? new Fold(FoldType.CODE, textArea, r.openOffset())
                        : ((Fold) stack.peek()[1]).createChild(FoldType.CODE, r.openOffset());
                if (stack.isEmpty()) folds.add(fold);
                // Fin del fold = último carácter (salto de línea) de la línea C−1
                fold.setEndOffset(textArea.getLineEndOffset(r.closeLine() - 1) - 1);
                stack.push(new Object[]{r, fold});
            }
        } catch (BadLocationException ex) {
            // Documento cambiando mientras se analizaba: devolver lo construido
        }
        return folds;
    }

    private static List<Range> findRanges(RSyntaxTextArea textArea) throws BadLocationException {
        String text = textArea.getText();
        var result = new ArrayList<Range>();
        var open = new ArrayDeque<int[]>();           // {char, offset, line}
        boolean inString = false;
        int line = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n') { line++; inString = false; continue; } // strings no cruzan líneas en JSON
            if (inString) {
                if (c == '\\') i++;                   // saltar el carácter escapado
                else if (c == '"') inString = false;
                continue;
            }
            switch (c) {
                case '"' -> inString = true;
                case '{', '[' -> open.push(new int[]{c, i, line});
                case '}', ']' -> {
                    char expected = c == '}' ? '{' : '[';
                    if (!open.isEmpty() && open.peek()[0] == expected) {
                        int[] o = open.pop();
                        if (line - o[2] >= 2) {
                            result.add(new Range(o[1], i, o[2], line));
                        }
                    }
                }
                default -> { }
            }
        }
        return result;
    }
}
