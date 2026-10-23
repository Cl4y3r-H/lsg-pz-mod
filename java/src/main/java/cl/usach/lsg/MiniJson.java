package cl.usach.lsg;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parser JSON mínimo, sin dependencias (el mod no puede asumir bibliotecas externas).
 * Objetos → Map<String,Object>, arreglos → List<Object>, números → Double,
 * textos → String, true/false → Boolean, null → null.
 */
public final class MiniJson {

    private final String s;
    private int i;

    private MiniJson(String s) { this.s = s; }

    public static Object parse(String text) {
        MiniJson p = new MiniJson(text == null ? "" : text.trim());
        Object v = p.value();
        p.ws();
        if (p.i != p.s.length()) throw p.error("contenido extra al final");
        return v;
    }

    // ------------------------------------------------------------ ayudas de lectura
    @SuppressWarnings("unchecked")
    public static Map<String, Object> obj(Object o) {
        return o instanceof Map ? (Map<String, Object>) o : null;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> arr(Object o) {
        return o instanceof List ? (List<Object>) o : null;
    }

    public static String str(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v == null ? null : String.valueOf(v);
    }

    public static Long num(Map<String, Object> m, String key) {
        Object v = m == null ? null : m.get(key);
        return v instanceof Number ? Long.valueOf(((Number) v).longValue()) : null;
    }

    // ------------------------------------------------------------------ parser
    private Object value() {
        ws();
        if (i >= s.length()) throw error("fin inesperado");
        char c = s.charAt(i);
        switch (c) {
            case '{': return object();
            case '[': return array();
            case '"': return string();
            case 't': expect("true"); return Boolean.TRUE;
            case 'f': expect("false"); return Boolean.FALSE;
            case 'n': expect("null"); return null;
            default: return number();
        }
    }

    private Map<String, Object> object() {
        Map<String, Object> m = new LinkedHashMap<>();
        i++; ws();
        if (peek('}')) { i++; return m; }
        while (true) {
            ws();
            String k = string();
            ws(); need(':');
            m.put(k, value());
            ws();
            if (peek(',')) { i++; continue; }
            need('}');
            return m;
        }
    }

    private List<Object> array() {
        List<Object> a = new ArrayList<>();
        i++; ws();
        if (peek(']')) { i++; return a; }
        while (true) {
            a.add(value());
            ws();
            if (peek(',')) { i++; continue; }
            need(']');
            return a;
        }
    }

    private String string() {
        need('"');
        StringBuilder b = new StringBuilder();
        while (i < s.length()) {
            char c = s.charAt(i++);
            if (c == '"') return b.toString();
            if (c != '\\') { b.append(c); continue; }
            if (i >= s.length()) break;
            char e = s.charAt(i++);
            switch (e) {
                case 'n': b.append('\n'); break;
                case 't': b.append('\t'); break;
                case 'r': b.append('\r'); break;
                case 'b': b.append('\b'); break;
                case 'f': b.append('\f'); break;
                case 'u':
                    if (i + 4 > s.length()) throw error("escape \\u incompleto");
                    b.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                    i += 4;
                    break;
                default: b.append(e);   // \" \\ \/
            }
        }
        throw error("texto sin cerrar");
    }

    private Double number() {
        int start = i;
        while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) i++;
        if (start == i) throw error("valor inesperado '" + s.charAt(i) + "'");
        try {
            return Double.valueOf(s.substring(start, i));
        } catch (NumberFormatException ex) {
            throw error("número inválido");
        }
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }
    private boolean peek(char c) { return i < s.length() && s.charAt(i) == c; }
    private void need(char c) { if (!peek(c)) throw error("se esperaba '" + c + "'"); i++; }
    private void expect(String word) {
        if (!s.startsWith(word, i)) throw error("se esperaba " + word);
        i += word.length();
    }
    private IllegalArgumentException error(String msg) {
        return new IllegalArgumentException("JSON inválido en posición " + i + ": " + msg);
    }
}
