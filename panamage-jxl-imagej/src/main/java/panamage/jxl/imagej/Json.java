package panamage.jxl.imagej;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A small JSON reader and writer for the metadata box, so that the plugin
 * needs no JSON library of its own next to the ones of Fiji.
 * <p>
 * Values are {@link Map} (with string keys, in their order), {@link List},
 * {@link String}, {@link Number} (read as {@link Double}), {@link Boolean} and
 * {@code null}.
 */
final class Json {

    /** The deepest nesting of arrays and objects that is read. */
    private static final int MAX_DEPTH = 64;

    private final String text;
    private int pos;

    private Json(String text) {
        this.text = text;
    }

    /**
     * Reads a JSON text.
     *
     * @param text the text
     * @return the value
     * @throws IllegalArgumentException if the text is not valid JSON
     */
    static Object parse(String text) {
        Json json = new Json(text);
        Object value = json.value(0);
        json.skipWhitespace();
        if (json.pos != text.length()) {
            throw json.error("Unexpected text after the value");
        }
        return value;
    }

    /**
     * Writes a value as JSON text.
     *
     * @param value the value
     * @return the text
     * @throws IllegalArgumentException if the value contains an unsupported
     *                                  type or a number that is not finite
     */
    static String write(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        switch (value) {
            case null -> out.append("null");
            case String s -> writeString(s, out);
            case Boolean b -> out.append(b);
            case Integer i -> out.append(i);
            case Long l -> out.append(l);
            case Number n -> {
                double d = n.doubleValue();
                if (!Double.isFinite(d)) {
                    throw new IllegalArgumentException("JSON has no number " + d);
                }
                if (d == Math.rint(d) && Math.abs(d) < 1e15) {
                    out.append((long) d);
                } else {
                    out.append(d);
                }
            }
            case Map<?, ?> map -> {
                out.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!first) {
                        out.append(',');
                    }
                    first = false;
                    writeString((String) entry.getKey(), out);
                    out.append(':');
                    write(entry.getValue(), out);
                }
                out.append('}');
            }
            case List<?> list -> {
                out.append('[');
                for (int i = 0; i < list.size(); i++) {
                    if (i > 0) {
                        out.append(',');
                    }
                    write(list.get(i), out);
                }
                out.append(']');
            }
            default -> throw new IllegalArgumentException("Unsupported JSON value: " + value.getClass());
        }
    }

    private static void writeString(String s, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) {
            throw error("Nested too deeply");
        }
        skipWhitespace();
        if (pos >= text.length()) {
            throw error("Unexpected end");
        }
        char c = text.charAt(pos);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        Map<String, Object> map = new LinkedHashMap<>();
        pos++;
        skipWhitespace();
        if (peek() == '}') {
            pos++;
            return map;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') {
                throw error("Expected a key");
            }
            String key = string();
            skipWhitespace();
            expect(':');
            map.put(key, value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == '}') {
                return map;
            }
            if (c != ',') {
                throw error("Expected ',' or '}'");
            }
        }
    }

    private List<Object> array(int depth) {
        List<Object> list = new ArrayList<>();
        pos++;
        skipWhitespace();
        if (peek() == ']') {
            pos++;
            return list;
        }
        while (true) {
            list.add(value(depth + 1));
            skipWhitespace();
            char c = next();
            if (c == ']') {
                return list;
            }
            if (c != ',') {
                throw error("Expected ',' or ']'");
            }
        }
    }

    private String string() {
        pos++;
        StringBuilder out = new StringBuilder();
        while (true) {
            char c = next();
            if (c == '"') {
                return out.toString();
            }
            if (c < 0x20) {
                throw error("Control character in a string");
            }
            if (c != '\\') {
                out.append(c);
                continue;
            }
            char escape = next();
            switch (escape) {
                case '"', '\\', '/' -> out.append(escape);
                case 'b' -> out.append('\b');
                case 'f' -> out.append('\f');
                case 'n' -> out.append('\n');
                case 'r' -> out.append('\r');
                case 't' -> out.append('\t');
                case 'u' -> {
                    if (pos + 4 > text.length()) {
                        throw error("Unexpected end");
                    }
                    try {
                        out.append((char) Integer.parseInt(text, pos, pos + 4, 16));
                    } catch (NumberFormatException e) {
                        throw error("Invalid escape");
                    }
                    pos += 4;
                }
                default -> throw error("Invalid escape");
            }
        }
    }

    private Object literal(String word, Object value) {
        if (!text.startsWith(word, pos)) {
            throw error("Unexpected text");
        }
        pos += word.length();
        return value;
    }

    private Double number() {
        int start = pos;
        while (pos < text.length() && "+-0123456789.eE".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
        String number = text.substring(start, pos);
        if (number.isEmpty() || !number.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) {
            pos = start;
            throw error("Invalid number");
        }
        return Double.valueOf(number);
    }

    private void skipWhitespace() {
        while (pos < text.length() && " \t\n\r".indexOf(text.charAt(pos)) >= 0) {
            pos++;
        }
    }

    private char peek() {
        if (pos >= text.length()) {
            throw error("Unexpected end");
        }
        return text.charAt(pos);
    }

    private char next() {
        char c = peek();
        pos++;
        return c;
    }

    private void expect(char c) {
        if (next() != c) {
            pos--;
            throw error("Expected '" + c + "'");
        }
    }

    private IllegalArgumentException error(String message) {
        return new IllegalArgumentException(message + " at position " + pos);
    }
}
