/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.arith;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;

public class Negator extends InstanceFactory {
    static final int IN  = 0;
    static final int OUT = 1;
    static final int SIGN_SEL = 2;

    // === Atributo de modo de signo ===
    public static final AttributeOption MODE_UNSIGNED
            = new AttributeOption("unsigned", "unsigned", Strings.getter("unsignedOption"));
    public static final AttributeOption MODE_SIGNED
            = new AttributeOption("signed", "signed",  Strings.getter("signedOption"));
    public static final AttributeOption MODE_PIN
            = new AttributeOption("pin", "pin", Strings.getter("pinOption"));
    public static final AttributeOption MODE_AUTO
            = new AttributeOption("auto", "auto", Strings.getter("autoOption"));

    public static final Attribute<AttributeOption> SIGN_MODE =
            Attributes.forOption("signMode", Strings.getter("arithSignMode"),
                    new AttributeOption[]{ MODE_UNSIGNED, MODE_SIGNED, MODE_PIN, MODE_AUTO });

    public Negator() {
        super("Negator", Strings.getter("negatorComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.WIDTH, SIGN_MODE },
                new Object[]   { BitWidth.create(8), MODE_UNSIGNED }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("negator.gif");
    }

    @Override
    protected void configureNewInstance(Instance instance) {
        instance.addAttributeListener();
        updatePorts(instance);
    }

    @Override
    protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        if (attr == SIGN_MODE || attr == StdAttr.WIDTH) {
            updatePorts(instance);
            instance.recomputeBounds();
            instance.fireInvalidated();
        }
    }

    private static boolean pinModeEnabled(Instance instance) {
        AttributeOption mode = instance.getAttributeValue(SIGN_MODE);
        return mode == MODE_PIN;
    }

    private void updatePorts(Instance instance) {
        BitWidth w = instance.getAttributeValue(StdAttr.WIDTH);
        boolean pinMode = pinModeEnabled(instance);

        Port in  = new Port(-40,  0, Port.INPUT,  w);
        Port out = new Port(  0,  0, Port.OUTPUT, w);
        in.setToolTip(Strings.getter("negatorInputTip"));
        out.setToolTip(Strings.getter("negatorOutputTip"));

        if (pinMode) {
            // pin de selección de signo (1=signed, 0/NIL=unsigned)
            Port signSel = new Port(-20, 20, Port.INPUT, BitWidth.ONE);
            signSel.setToolTip(Strings.getter("negatorSignSelTip"));
            instance.setPorts(new Port[]{ in, out, signSel });
        } else {
            instance.setPorts(new Port[]{ in, out });
        }
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth width = state.getAttributeValue(StdAttr.WIDTH);
        AttributeOption modeOpt = state.getAttributeValue(SIGN_MODE);

        Value in = state.getPort(IN);
        Value out;

        // Decidir semántica de signo
        boolean signed = decideSigned(state, modeOpt, width);

        if (in.isFullyDefined()) {
            out = negateFullyDefined(width, in, signed);
        } else {
            // Mantén tu comportamiento “cuidadoso” para indefinidos/errores
            out = negateUnknown(width, in);
        }

        int delay = (width.getWidth() + 2) * Adder.PER_DELAY;
        state.setPort(OUT, out, delay);
    }

    private static boolean decideSigned(InstanceState st, AttributeOption mode, BitWidth w) {
        if (mode == MODE_SIGNED)   return true;
        if (mode == MODE_UNSIGNED) return false;

        if (mode == MODE_PIN) {
            // si existe y vale 1 → signed; 0/NIL/unknown → unsigned
            try {
                Value sel = st.getPort(SIGN_SEL);
                return sel == Value.TRUE;
            } catch (Exception ignore) {
                return false;
            }
        }

        // AUTO: heurística simple por MSB si está totalmente definido
        Value in = st.getPort(IN);
        if (in != null && in.isFullyDefined()) {
            int vi = in.toIntValue();
            return msbSet(vi, w.getWidth());
        }
        return false; // por defecto unsigned si no se puede decidir
    }

    private static Value negateFullyDefined(BitWidth w, Value in, boolean signed) {
        int width = w.getWidth();
        int vi = in.toIntValue();

        long mask = mask(width);
        long val;
        if (signed) {
            long sv = signExtend(vi, width);
            val = (-sv) & mask;
        } else {
            long uv = unsigned(vi, width);
            val = (-uv) & mask;
        }
        return Value.createKnown(w, (int) val);
    }

    /**
     * Rutina de negación para casos no completamente definidos.
     * Conserva la idea original: propaga UNKNOWN/ERROR con una aproximación estable.
     * (No depende del modo de signo: con indefinidos no hay semántica aritmética fiable.)
     */
    private static Value negateUnknown(BitWidth w, Value in) {
        Value[] bits = in.getAll();
        Value fill = Value.FALSE;
        int pos = 0;
        while (pos < bits.length) {
            if (bits[pos] == Value.FALSE) {
                bits[pos] = fill;
            } else if (bits[pos] == Value.TRUE) {
                if (fill != Value.FALSE) bits[pos] = fill;
                pos++;
                break;
            } else if (bits[pos] == Value.ERROR) {
                fill = Value.ERROR;
            } else {
                if (fill == Value.FALSE) fill = bits[pos];
                else bits[pos] = fill;
            }
            pos++;
        }
        while (pos < bits.length) {
            if (bits[pos] == Value.TRUE) {
                bits[pos] = Value.FALSE;
            } else if (bits[pos] == Value.FALSE) {
                bits[pos] = Value.TRUE;
            }
            pos++;
        }
        return Value.create(bits);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        painter.drawBounds();
        painter.drawPort(IN);
        painter.drawPort(OUT, "-x", Direction.WEST);
        if (pinModeEnabled(painter.getInstance())) {
            painter.drawPort(SIGN_SEL);
        }

        // Etiqueta S/U/P/A según el modo
        AttributeOption mode = painter.getAttributeValue(SIGN_MODE);
        String tag = (mode == MODE_SIGNED) ? "S"
                : (mode == MODE_UNSIGNED ? "U"
                : (mode == MODE_PIN ? "P" : "A"));
        java.awt.Graphics g = painter.getGraphics();
        g.setColor(java.awt.Color.DARK_GRAY);
        Location loc = painter.getLocation();
        g.drawString(tag, loc.getX() - 30, loc.getY() + 5);
    }

    /* ===================== helpers numéricos ===================== */

    private static long mask(int w) {
        return (w >= 63) ? -1L : ((1L << w) - 1L);
    }

    private static boolean msbSet(int val, int w) {
        if (w <= 0) return false;
        int bit = 1 << (w - 1);
        return (val & bit) != 0;
    }

    private static long unsigned(int val, int w) {
        return ((long) val) & mask(w);
    }

    private static long signExtend(int val, int w) {
        long u = ((long) val) & mask(w);
        long sign = 1L << (w - 1);
        return (u ^ sign) - sign; // extensión de signo
    }
}
