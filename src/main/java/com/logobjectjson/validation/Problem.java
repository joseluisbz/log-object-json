package com.logobjectjson.validation;

/**
 * A problem found in a text (JSON or log): where it is and what is wrong.
 *
 * @param offset   start offset in the text
 * @param length   length of the problematic fragment (at least 1)
 * @param severity ERROR (makes the text invalid) or WARNING (valid but suspicious)
 * @param message  user-facing message (already localized)
 */
public record Problem(int offset, int length, Severity severity, String message) {

    public enum Severity { ERROR, WARNING }

    public Problem {
        offset = Math.max(0, offset);
        length = Math.max(1, length);
    }

    public boolean isError() {
        return severity == Severity.ERROR;
    }
}
