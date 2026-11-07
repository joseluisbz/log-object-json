package com.logobjectjson.parser;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Span of the parsed text that corresponds to a property or element:
 * from the start of the key (or element) to the end of its value.
 *
 * The path is stored LINKED (segment + parent) and the full list is only built
 * on request: copying it into every span would be O(depth²) in memory and time.
 *
 * @param start start offset (absolute in the whole text, after LogExtractor)
 * @param end   end offset (exclusive)
 * @param link  last path segment linked to its ancestors (null = root)
 */
public record Span(int start, int end, Path link) {

    /** Linked path segment: String = key, Integer = index. */
    public record Path(Object segment, Path parent) {
        public List<Object> list() {
            var out = new ArrayList<Object>();
            for (Path p = this; p != null; p = p.parent) out.add(p.segment);
            Collections.reverse(out);
            return out;
        }
    }

    /** Full path from the entry root: String = key, Integer = index. */
    public List<Object> path() {
        return link == null ? List.of() : link.list();
    }

    public boolean contains(int offset) {
        return offset >= start && offset < end;
    }

    public int length() {
        return end - start;
    }

    public Span shifted(int base) {
        return new Span(start + base, end + base, link);
    }
}
