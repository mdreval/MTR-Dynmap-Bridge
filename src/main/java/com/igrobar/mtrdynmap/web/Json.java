package com.igrobar.mtrdynmap.web;

/**
 * Minimal JSON writer without external dependencies.
 * Coordinates are rounded to 0.01 to keep the data sent to browsers small.
 */
public final class Json {
    private final StringBuilder out;
    private boolean comma;

    public Json(int capacity) {
        this.out = new StringBuilder(capacity);
    }

    public Json beginObject() {
        this.separator();
        this.out.append('{');
        this.comma = false;
        return this;
    }

    public Json endObject() {
        this.out.append('}');
        this.comma = true;
        return this;
    }

    public Json beginArray() {
        this.separator();
        this.out.append('[');
        this.comma = false;
        return this;
    }

    public Json endArray() {
        this.out.append(']');
        this.comma = true;
        return this;
    }

    /** Object key; the next value is written right after it. */
    public Json key(String name) {
        this.separator();
        string(this.out, name);
        this.out.append(':');
        this.comma = false;
        return this;
    }

    public Json value(String value) {
        this.separator();
        if (value == null) {
            this.out.append("null");
        } else {
            string(this.out, value);
        }
        this.comma = true;
        return this;
    }

    public Json value(boolean value) {
        this.separator();
        this.out.append(value);
        this.comma = true;
        return this;
    }

    public Json value(long value) {
        this.separator();
        this.out.append(value);
        this.comma = true;
        return this;
    }

    public Json value(double value) {
        this.separator();
        number(this.out, value);
        this.comma = true;
        return this;
    }

    /** Point [x, y, z]. */
    public Json point(double x, double y, double z) {
        this.separator();
        this.out.append('[');
        number(this.out, x);
        this.out.append(',');
        number(this.out, y);
        this.out.append(',');
        number(this.out, z);
        this.out.append(']');
        this.comma = true;
        return this;
    }

    public Json field(String name, String value) {
        return this.key(name).value(value);
    }

    public Json field(String name, boolean value) {
        return this.key(name).value(value);
    }

    public Json field(String name, long value) {
        return this.key(name).value(value);
    }

    public Json field(String name, double value) {
        return this.key(name).value(value);
    }

    @Override
    public String toString() {
        return this.out.toString();
    }

    private void separator() {
        if (this.comma) {
            this.out.append(',');
        }
    }

    private static void number(StringBuilder out, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            out.append('0');
            return;
        }
        long scaled = Math.round(value * 100.0);
        if (scaled % 100 == 0) {
            out.append(scaled / 100);
            return;
        }
        if (scaled < 0) {
            out.append('-');
            scaled = -scaled;
        }
        out.append(scaled / 100).append('.');
        long fraction = scaled % 100;
        if (fraction < 10) {
            out.append('0').append(fraction);
        } else if (fraction % 10 == 0) {
            out.append(fraction / 10);
        } else {
            out.append(fraction);
        }
    }

    public static String quote(String value) {
        StringBuilder sb = new StringBuilder(value.length() + 2);
        string(sb, value);
        return sb.toString();
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '<' -> out.append("\\u003c");
                case '>' -> out.append("\\u003e");
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
}
