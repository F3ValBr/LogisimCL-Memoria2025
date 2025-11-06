/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.arith;

import java.awt.Color;
import java.awt.Graphics;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

public class Divider extends InstanceFactory {
	static final int PER_DELAY = 1;

    static final int IN0   = 0; // dividend lower
    static final int IN1   = 1; // divisor
    static final int OUT   = 2; // quotient (low w bits)
    static final int UPPER = 3; // dividend upper
    static final int REM   = 4; // remainder (low w bits)
    static final int SIGN_SEL = 5; // sign select (only in pin mode)

    // ===== Atributo de modo (Unsigned / Signed / Pin / Auto) =====
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

    // ===== Modo de división: Trunc o Floor =====
    public static final AttributeOption DIV_TRUNC
            = new AttributeOption("trunc", "trunc", Strings.getter("truncOption"));
    public static final AttributeOption DIV_FLOOR
            = new AttributeOption("floor", "floor", Strings.getter("floorOption"));

    public static final Attribute<AttributeOption> DIV_MODE =
            Attributes.forOption("divMode", Strings.getter("divMode"),
                    new AttributeOption[]{ DIV_TRUNC, DIV_FLOOR });

    public Divider() {
        super("Divider", Strings.getter("dividerComponent"));
        setAttributes(
                new Attribute[] { StdAttr.WIDTH, SIGN_MODE, DIV_MODE },
                new Object[]   { BitWidth.create(8), MODE_UNSIGNED, DIV_TRUNC });
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("divider.gif");
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
        } else if (attr == DIV_MODE) {
            instance.fireInvalidated();
        }
    }

    private static boolean pinModeEnabled(Instance instance) {
        return instance.getAttributeValue(SIGN_MODE) == MODE_PIN;
    }

    private void updatePorts(Instance instance) {
        BitWidth w = instance.getAttributeValue(StdAttr.WIDTH);
        boolean pinMode = pinModeEnabled(instance);

        Port in0   = new Port(-40, -10, Port.INPUT,  w);
        Port in1   = new Port(-40,  10, Port.INPUT,  w);
        Port out   = new Port(  0,   0, Port.OUTPUT, w);
        Port upper = new Port(-20, -20, Port.INPUT,  w);
        Port rem   = new Port(-20,  20, Port.OUTPUT, w);

        in0.setToolTip(Strings.getter("dividerDividendLowerTip"));
        in1.setToolTip(Strings.getter("dividerDivisorTip"));
        out.setToolTip(Strings.getter("dividerOutputTip"));
        upper.setToolTip(Strings.getter("dividerDividendUpperTip"));
        rem.setToolTip(Strings.getter("dividerRemainderTip"));

        if (pinMode) {
            Port signSel = new Port(-30, 20, Port.INPUT, BitWidth.ONE);
            signSel.setToolTip(Strings.getter("dividerSignSelTip"));
            instance.setPorts(new Port[]{ in0, in1, out, upper, rem, signSel });
        } else {
            instance.setPorts(new Port[]{ in0, in1, out, upper, rem });
        }
    }

    @Override
    public void propagate(InstanceState state) {
        // get attributes
        BitWidth width = state.getAttributeValue(StdAttr.WIDTH);
        AttributeOption signOpt = state.getAttributeValue(SIGN_MODE);
        AttributeOption divOpt  = state.getAttributeValue(DIV_MODE);

        // compute outputs
        Value lo    = state.getPort(IN0);
        Value den   = state.getPort(IN1);
        Value upper = state.getPort(UPPER);

        boolean signed = decideSigned(state, signOpt, width, lo, den, upper);
        Value[] outs = computeResult(width, lo, den, upper, signed, divOpt);

        // propagate them
        int w = width.getWidth();
        int delay = w * (w + 2) * PER_DELAY;
        state.setPort(OUT, outs[0], delay);
        state.setPort(REM, outs[1], delay);
    }

    private static boolean decideSigned(InstanceState st, AttributeOption mode, BitWidth w,
                                        Value lo, Value den, Value upper) {
        if (mode == MODE_SIGNED)  return true;
        if (mode == MODE_UNSIGNED) return false;

        if (mode == MODE_PIN) {
            // Si el pin existe y está a 1 → signed. Si 0/NC/X → unsigned.
            try {
                Value sel = st.getPort(SIGN_SEL);
                return sel == Value.TRUE;
            } catch (IndexOutOfBoundsException ex) {
                return false; // por si acaso, si no existe el puerto
            }
        }

        // AUTO: heurística por MSB (si upper no conectado → 0)
        int widthBits = w.getWidth();
        boolean upperDisconnected = (upper == Value.NIL || upper.isUnknown());
        int loI  = lo.isFullyDefined()  ? lo.toIntValue()  : 0;
        int denI = den.isFullyDefined() ? den.toIntValue() : 0;
        int upI  = upper.isFullyDefined()? upper.toIntValue() : 0;

        if (upperDisconnected) {
            return msbSet(loI, widthBits) || msbSet(denI, widthBits);
        } else {
            return msbSet(upI, widthBits) || msbSet(denI, widthBits);
        }
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();

        g.setColor(Color.GRAY);
        painter.drawPort(IN0);
        painter.drawPort(IN1);
        painter.drawPort(OUT);
        painter.drawPort(UPPER, Strings.get("dividerUpperInput"),  Direction.NORTH);
        painter.drawPort(REM, Strings.get("dividerRemainderOutput"), Direction.SOUTH);
        if (pinModeEnabled(painter.getInstance())) {
            painter.drawPort(SIGN_SEL);
        }

        Location loc = painter.getLocation();
        int x = loc.getX(), y = loc.getY();
        GraphicsUtil.switchToWidth(g, 2);
        g.setColor(Color.BLACK);
        g.fillOval(x - 12, y - 7, 4, 4);
        g.drawLine(x - 15, y, x - 5, y);
        g.fillOval(x - 12, y + 3, 4, 4);
        GraphicsUtil.switchToWidth(g, 1);

        // Etiqueta de modo (U/S/P/A) + (T/F)
        try {
            AttributeOption sm = painter.getAttributeValue(SIGN_MODE);
            String sTag = (sm == MODE_SIGNED) ? "S"
                    : (sm == MODE_UNSIGNED ? "U"
                    : (sm == MODE_PIN ? "P" : "A"));
            g.setColor(Color.DARK_GRAY);
            g.drawString(sTag, x - 30, y + 5);
        } catch (Exception ignore) { }
    }

    /* ===================== Núcleo con modo ===================== */

    static Value[] computeResult(BitWidth width, Value lo, Value den, Value upper,
                                 boolean signed, AttributeOption divOpt) {
        int w = width.getWidth();
        boolean upperDisconnected = (upper == Value.NIL || upper.isUnknown());

        // Si upper no está conectado, tratar como 0
        if (upperDisconnected) {
            upper = Value.createKnown(width, 0);
        }

        // Manejo de indefinidos
        if (!(lo.isFullyDefined() && den.isFullyDefined() && upper.isFullyDefined())) {
            if (lo.isErrorValue() || den.isErrorValue() || upper.isErrorValue()) {
                return new Value[]{ Value.createError(width), Value.createError(width) };
            } else {
                return new Value[]{ Value.createUnknown(width), Value.createUnknown(width) };
            }
        }

        int loI    = lo.toIntValue();
        int upI    = upper.toIntValue();
        int denI   = den.toIntValue();

        long maskW = mask(w);
        long loU   = ((long) loI) & maskW;
        long upU   = ((long) upI) & maskW;

        long num, denL;
        if (signed) {
            // Dividendo 2w-bits con signo: (upper sign-extend) << w | (lo unsigned)
            long upS = signExtend(upI, w);
            num  = (upS << w) | loU;
            denL = signExtend(denI, w);
        } else {
            // Unsigned
            num  = (upU << w) | loU;
            denL = ((long) denI) & maskW;
        }

        if (denL == 0) denL = 1; // evita /0

        // División truncada base
        long q0 = num / denL; // trunc hacia 0
        long r0 = num % denL;

        if (divOpt == DIV_FLOOR) {
            // Ajuste a floor: si resto != 0 y los signos de num y den difieren
            if (r0 != 0 && ((num ^ denL) < 0)) {
                r0 += denL;
                q0--;
            }
        }

        // Salida: bajo w bits
        long qOut = q0 & maskW;
        long rOut = r0 & maskW;

        return new Value[]{
                Value.createKnown(width, (int) qOut),
                Value.createKnown(width, (int) rOut)
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

    private static long signExtend(int val, int w) {
        long u = ((long) val) & mask(w);
        long sign = 1L << (w - 1);
        return (u ^ sign) - sign;
    }
}
