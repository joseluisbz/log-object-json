package com.logobjectjson.ui.fold;

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
 * Postman-style foldable range parser for JSON.
 *
 * Rules:
 *  • Pairs { } and [ ] ignoring those inside strings (and \" escapes).
 *  • A range opens on line A and closes on line C; it is foldable only if C − A ≥ 2.
 *  • The RSTA Fold ends at the end of line C−1: collapsing hides A+1 … C−1
 *    and lines A and C stay visible (like Postman).
 *  • Closings that do not match the pending opening are ignored (edited/malformed JSON).
 */
public class JsonFoldParser implements FoldParser {

    /** Matched range (bracket offsets and their lines). */
    private record Range(int openOffset, int closeOffset, int openLine, int closeLine) { }

    @Override
    public List<Fold> getFolds(RSyntaxTextArea textArea) {
        var folds = new ArrayList<Fold>();
        try {
            List<Range> ranges = findRanges(textArea);
            ranges.sort(Comparator.comparingInt(Range::openOffset));

            // Build the Fold tree respecting the nesting
            var stack = new ArrayDeque<Object[]>();   // {Range, Fold}
            for (Range r : ranges) {
                while (!stack.isEmpty() && ((Range) stack.peek()[0]).closeOffset() < r.openOffset()) {
                    stack.pop();
                }
                Fold fold = stack.isEmpty()
                        ? new Fold(FoldType.CODE, textArea, r.openOffset())
                        : ((Fold) stack.peek()[1]).createChild(FoldType.CODE, r.openOffset());
                if (stack.isEmpty()) folds.add(fold);
                // Fold end = last character (line break) of line C−1
                fold.setEndOffset(textArea.getLineEndOffset(r.closeLine() - 1) - 1);
                stack.push(new Object[]{r, fold});
            }
        } catch (BadLocationException ex) {
            // Document changed while parsing: return what was built
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
            if (c == '\n') { line++; inString = false; continue; } // strings do not span lines in JSON
            if (inString) {
                if (c == '\\') i++;                   // skip the escaped character
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
