/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.arith;

import java.awt.Color;
import java.awt.Graphics;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

public class Multiplier extends InstanceFactory {
	static final int PER_DELAY = 1;

    static final int IN0   = 0;
    static final int IN1   = 1;
    static final int OUT   = 2;
    static final int C_IN  = 3;
    static final int C_OUT = 4;
    static final int SIGN_SEL = 5;

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

    public Multiplier() {
        super("Multiplier", Strings.getter("multiplierComponent"));

        setAttributes(
                new Attribute[]{ StdAttr.WIDTH, SIGN_MODE },
                new Object[]   { BitWidth.create(8), MODE_UNSIGNED }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("multiplier.gif");
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

        // posiciones en múltiplos de 10
        Port in0   = new Port(-40, -10, Port.INPUT,  w);
        Port in1   = new Port(-40,  10, Port.INPUT,  w);
        Port out   = new Port(  0,   0, Port.OUTPUT, w);
        Port cin   = new Port(-20, -20, Port.INPUT,  w);
        Port cout  = new Port(-20,  20, Port.OUTPUT, w);

        in0.setToolTip(Strings.getter("multiplierInputTip"));
        in1.setToolTip(Strings.getter("multiplierInputTip"));
        out.setToolTip(Strings.getter("multiplierOutputTip"));
        cin.setToolTip(Strings.getter("multiplierCarryInTip"));
        cout.setToolTip(Strings.getter("multiplierCarryOutTip"));

        if (pinMode) {
            Port signSel = new Port(-30, 20, Port.INPUT, BitWidth.ONE); // arriba a la izquierda
            signSel.setToolTip(Strings.getter("multiplierSignSelTip")); // agrega clave en Strings
            instance.setPorts(new Port[]{ in0, in1, out, cin, cout, signSel });
        } else {
            instance.setPorts(new Port[]{ in0, in1, out, cin, cout });
        }
    }

    @Override
    public void propagate(InstanceState state) {
        // get attributes
        BitWidth width = state.getAttributeValue(StdAttr.WIDTH);
        AttributeOption modeOpt = state.getAttributeValue(SIGN_MODE);

        // compute outputs
        Value a    = state.getPort(IN0);
        Value b    = state.getPort(IN1);
        Value c_in = state.getPort(C_IN);

        // decide signo por modo (incluye el pin)
        boolean signed = decideSigned(state, modeOpt, width);

        Value[] outs = computeProduct(width, a, b, c_in, signed);

        // propagate them
        int w = width.getWidth();
        int delay = w * (w + 2) * PER_DELAY;
        state.setPort(OUT,   outs[0], delay);
        state.setPort(C_OUT, outs[1], delay);
    }

    private static boolean decideSigned(InstanceState st, AttributeOption mode, BitWidth w) {
        if (mode == MODE_SIGNED)  return true;
        if (mode == MODE_UNSIGNED) return false;

        if (mode == MODE_PIN) {
            // si existe el pin y está a 1 → signed
            // si 0, NIL o no conectado → unsigned
            int idx = SIGN_SEL;
            // cuidado: el puerto puede no existir si no está en MODE_PIN (pero estamos en MODE_PIN)
            Value sel = st.getPort(idx);
            return sel == Value.TRUE; // 0, X, NIL → unsigned
        }

        // AUTO: heurística por MSB de A o B
        Value a = st.getPort(IN0);
        Value b = st.getPort(IN1);
        if (a.isFullyDefined() && b.isFullyDefined()) {
            int ai = a.toIntValue();
            int bi = b.toIntValue();
            return msbSet(ai, w.getWidth()) || msbSet(bi, w.getWidth());
        }
        return false; // si no se puede decidir, usa unsigned
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();

        g.setColor(Color.GRAY);
        painter.drawPort(IN0);
        painter.drawPort(IN1);
        painter.drawPort(OUT);
        painter.drawPort(C_IN,  "c in",  Direction.NORTH);
        painter.drawPort(C_OUT, "c out", Direction.SOUTH);
        if (pinModeEnabled(painter.getInstance())) {
            painter.drawPort(SIGN_SEL);
        }

        // Icono "X"
        Location loc = painter.getLocation();
        int x = loc.getX();
        int y = loc.getY();
        GraphicsUtil.switchToWidth(g, 2);
        g.setColor(Color.BLACK);
        g.drawLine(x - 15, y - 5, x - 5, y + 5);
        g.drawLine(x - 15, y + 5, x - 5, y - 5);
        GraphicsUtil.switchToWidth(g, 1);

        // Etiqueta de modo (S/U/A/P)
        AttributeOption mode = painter.getAttributeValue(SIGN_MODE);
        String tag = (mode == MODE_SIGNED) ? "S"
                : (mode == MODE_UNSIGNED ? "U"
                : (mode == MODE_PIN ? "P" : "A"));
        g.setColor(Color.DARK_GRAY);
        g.drawString(tag, x - 30, y + 5);
    }

    /* ===================== Núcleo de cálculo ===================== */

    private static Value[] computeProduct(BitWidth width, Value a, Value b, Value c_in, boolean signed) {
        int w = width.getWidth();
        if (c_in == Value.NIL || c_in.isUnknown()) c_in = Value.createKnown(width, 0);

        // Si hay indefinidos/errores, conserva el comportamiento original
        if (!(a.isFullyDefined() && b.isFullyDefined() && c_in.isFullyDefined())) {
            return computeProductUnknown(width, a, b, c_in);
        }

        // Interpretar entradas según el modo
        int ai = a.toIntValue();
        int bi = b.toIntValue();
        int ci = c_in.toIntValue();

        long av = signed ? signExtend(ai, w) : unsigned(ai, w);
        long bv = signed ? signExtend(bi, w) : unsigned(bi, w);
        long cv = signed ? signExtend(ci, w) : unsigned(ci, w);

        // Producto + c_in (en 2w bits conceptuales)
        long prod = av * bv + cv;

        long maskW = mask(w);
        long lo = prod & maskW;
        long hi = (prod >> w) & maskW;

        return new Value[]{
                Value.createKnown(width, (int) lo),
                Value.createKnown(width, (int) hi)
        };
    }

    // Mantiene el comportamiento original para UNKNOWN/ERROR
    private static Value[] computeProductUnknown(BitWidth width, Value a, Value b, Value c_in) {
        int w = width.getWidth();
        Value[] avals = a.getAll();
        int aOk = findUnknown(avals);
        int aErr = findError(avals);
        int ax = getKnown(avals);
        Value[] bvals = b.getAll();
        int bOk = findUnknown(bvals);
        int bErr = findError(bvals);
        int bx = getKnown(bvals);
        Value[] cvals = c_in.getAll();
        int cOk = findUnknown(cvals);
        int cErr = findError(cvals);
        int cx = getKnown(cvals);

        int known = Math.min(Math.min(aOk, bOk), cOk);
        int error = Math.min(Math.min(aErr, bErr), cErr);
        int ret = ax * bx + cx;

        Value[] bits = new Value[w];
        for (int i = 0; i < w; i++) {
            if (i < known) {
                bits[i] = ((ret & (1 << i)) != 0 ? Value.TRUE : Value.FALSE);
            } else if (i < error) {
                bits[i] = Value.UNKNOWN;
            } else {
                bits[i] = Value.ERROR;
            }
        }
        return new Value[]{
                Value.create(bits),
                error < w ? Value.createError(width) : Value.createUnknown(width)
        };
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
        return (u ^ sign) - sign; // ext. signo
    }

    private static int findUnknown(Value[] vals) {
        for (int i = 0; i < vals.length; i++) {
            if (!vals[i].isFullyDefined()) return i;
        }
        return vals.length;
    }

    private static int findError(Value[] vals) {
        for (int i = 0; i < vals.length; i++) {
            if (vals[i].isErrorValue()) return i;
        }
        return vals.length;
    }

    private static int getKnown(Value[] vals) {
        int ret = 0;
        for (int i = 0; i < vals.length; i++) {
            int v = vals[i].toIntValue();
            if (v < 0) return ret;
            ret |= v << i;
        }
        return ret;
    }
}
