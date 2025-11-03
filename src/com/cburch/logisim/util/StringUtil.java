/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.util;

public class StringUtil {
    private StringUtil() { }

    public static String capitalize(String a) {
        return Character.toTitleCase(a.charAt(0)) + a.substring(1);
    }

    /* ================== API PÚBLICA ================== */

    public static String format(String fmt, String... args) {
        return formatImpl(fmt, args);
    }

    public static String format(String fmt, String a1) {
        return formatImpl(fmt, a1);
    }

    public static String format(String fmt, String a1, String a2) {
        return formatImpl(fmt, a1, a2);
    }

    public static String format(String fmt, String a1, String a2, String a3) {
        return formatImpl(fmt, a1, a2, a3);
    }

    /* ================== IMPLEMENTACIÓN ================== */

    private static String formatImpl(String fmt, String... args) {
        if (fmt == null) return "";

        StringBuilder ret = new StringBuilder();
        int pos = 0;
        int next = fmt.indexOf('%');
        int argSeq = 0; // para los %s secuenciales

        // normalizamos args a no-nulos
        for (int i = 0; i < args.length; i++) {
            if (args[i] == null) args[i] = "(null)";
        }

        while (next >= 0) {
            // copiamos lo que hay antes del %
            ret.append(fmt, pos, next);

            // ¿queda al menos 1 char después del %?
            if (next + 1 >= fmt.length()) {
                // % al final → lo dejamos literal
                ret.append('%');
                pos = next + 1;
                break;
            }

            char c = fmt.charAt(next + 1);

            switch (c) {
                case 's' -> {
                    // %s → tomar siguiente argumento
                    String v = (argSeq < args.length) ? args[argSeq] : "(null)";
                    ret.append(v);
                    argSeq++;
                    pos = next + 2;
                }
                case '$' -> {
                    // %$1, %$2, %$3
                    if (next + 2 < fmt.length()) {
                        char which = fmt.charAt(next + 2);
                        int idx = switch (which) {
                            case '1' -> 0;
                            case '2' -> 1;
                            case '3' -> 2;
                            default -> -1;
                        };
                        if (idx >= 0 && idx < args.length) {
                            ret.append(args[idx]);
                            pos = next + 3;
                        } else {
                            // no es %$1..3 → lo dejamos casi literal
                            ret.append("%$");
                            pos = next + 2;
                        }
                    } else {
                        // %$ al final
                        ret.append("%$");
                        pos = next + 2;
                    }
                }
                case '%' -> {
                    // %% → un solo %
                    ret.append('%');
                    pos = next + 2;
                }
                default -> {
                    // %x desconocido → dejar % y seguir
                    ret.append('%');
                    pos = next + 1;
                }
            }

            next = fmt.indexOf('%', pos);
        }

        // resto del string
        if (pos < fmt.length()) {
            ret.append(fmt.substring(pos));
        }

        return ret.toString();
    }

    /* ================== GETTERS DE STRINGS ================== */

    public static StringGetter formatter(final StringGetter base, final String arg) {
        return () -> format(base.get(), arg);
    }

    public static StringGetter formatter(final StringGetter base, final StringGetter arg) {
        return () -> format(base.get(), arg.get());
    }

    public static StringGetter constantGetter(final String value) {
        return () -> value;
    }

    /* ================== OTROS ================== */

    public static String toHexString(int bits, int value) {
        if (bits < 32) value &= (1 << bits) - 1;
        String ret = Integer.toHexString(value);
        int len = (bits + 3) / 4;
        while (ret.length() < len) ret = "0" + ret;
        if (ret.length() > len) ret = ret.substring(ret.length() - len);
        return ret;
    }
}
