package navmn.buildlogic;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, dependency-free JSON reader for the build-time voice pack check (RFC 8259: objects, arrays, strings,
 * numbers, true, false, null; no comments, no trailing commas). Objects become {@link LinkedHashMap} (duplicate members
 * are an error), arrays {@link ArrayList}, numbers {@link Long} when integral without a fraction or exponent, otherwise
 * {@link BigDecimal}.
 */
public final class MiniJson {
    private final String s;
    private int i;

    private MiniJson(String s) {
        this.s = s;
    }

    /** Parses one JSON document; throws IllegalArgumentException on any syntax error. */
    public static Object parse(String text) {
        MiniJson p = new MiniJson(text);
        p.ws();
        Object v = p.value();
        p.ws();
        if (p.i != text.length()) throw p.error("trailing characters");
        return v;
    }

    private IllegalArgumentException error(String what) {
        return new IllegalArgumentException("JSON " + what + " at offset " + i);
    }

    private void ws() {
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') i++;
            else break;
        }
    }

    private Object value() {
        if (i >= s.length()) throw error("unexpected end");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': return literal("true", Boolean.TRUE);
            case 'f': return literal("false", Boolean.FALSE);
            case 'n': return literal("null", null);
            default:
                if (c == '-' || (c >= '0' && c <= '9')) return number();
                throw error("unexpected character");
        }
    }

    private Object literal(String word, Object v) {
        if (!s.startsWith(word, i)) throw error("bad literal");
        i += word.length();
        return v;
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++;
        ws();
        if (peek() == '}') {
            i++;
            return m;
        }
        while (true) {
            ws();
            if (peek() != '"') throw error("expected a member name");
            String k = string();
            ws();
            if (peek() != ':') throw error("expected ':'");
            i++;
            ws();
            if (m.containsKey(k)) throw error("duplicate member " + k);
            m.put(k, value());
            ws();
            char c = peek();
            i++;
            if (c == '}') return m;
            if (c != ',') throw error("expected ',' or '}'");
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++;
        ws();
        if (peek() == ']') {
            i++;
            return a;
        }
        while (true) {
            ws();
            a.add(value());
            ws();
            char c = peek();
            i++;
            if (c == ']') return a;
            if (c != ',') throw error("expected ',' or ']'");
        }
    }

    private char peek() {
        if (i >= s.length()) throw error("unexpected end");
        return s.charAt(i);
    }

    private String string() {
        StringBuilder sb = new StringBuilder();
        i++;
        while (true) {
            char c = peek();
            i++;
            if (c == '"') return sb.toString();
            if (c < 0x20) throw error("control character in a string");
            if (c != '\\') {
                sb.append(c);
                continue;
            }
            char e = peek();
            i++;
            switch (e) {
                case '"': sb.append('"'); break;
                case '\\': sb.append('\\'); break;
                case '/': sb.append('/'); break;
                case 'b': sb.append('\b'); break;
                case 'f': sb.append('\f'); break;
                case 'n': sb.append('\n'); break;
                case 'r': sb.append('\r'); break;
                case 't': sb.append('\t'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("bad \\u escape");
                    try {
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    } catch (NumberFormatException ex) {
                        throw error("bad \\u escape");
                    }
                    i += 4;
                    break;
                default: throw error("bad escape");
            }
        }
    }

    private Object number() {
        int start = i;
        if (peek() == '-') i++;
        if (i < s.length() && s.charAt(i) == '0') i++;
        else digits();
        boolean integral = true;
        if (i < s.length() && s.charAt(i) == '.') {
            integral = false;
            i++;
            digits();
        }
        if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
            integral = false;
            i++;
            if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
            digits();
        }
        String t = s.substring(start, i);
        if (integral) {
            try {
                return Long.parseLong(t);
            } catch (NumberFormatException ex) {
                return new BigDecimal(t);
            }
        }
        return new BigDecimal(t);
    }

    private void digits() {
        int start = i;
        while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
        if (i == start) throw error("expected a digit");
    }
}
