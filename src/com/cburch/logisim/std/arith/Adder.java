/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.arith;

import java.awt.Color;
import java.awt.Graphics;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

public class Adder extends InstanceFactory {
	static final int PER_DELAY = 1;

    static final int IN0   = 0;
    static final int IN1   = 1;
    static final int OUT   = 2;
    static final int C_IN  = 3;
    static final int C_OUT = 4;
    static final int SIGN_SEL = 5;

    // ===== Modo de signo =====
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

    public Adder() {
        super("Adder", Strings.getter("adderComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.WIDTH, SIGN_MODE },
                new Object[]   { BitWidth.create(8), MODE_UNSIGNED }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("adder.gif");
    }

    @Override
    protected void configureNewInstance(Instance instance) {
        instance.addAttributeListener();
        updatePorts(instance);
    }

    @Override
    protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        if (attr == SIGN_MODE || attr == StdAttr.WIDTH) {
            updatePorts(instance);         // añade/quita SIGN_SEL según modo
            instance.recomputeBounds();
            instance.fireInvalidated();    // limpia halos/zonas de conexión
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
        Port cin   = new Port(-20, -20, Port.INPUT,  1);
        Port cout  = new Port(-20,  20, Port.OUTPUT, 1);

        in0.setToolTip(Strings.getter("adderInputTip"));
        in1.setToolTip(Strings.getter("adderInputTip"));
        out.setToolTip(Strings.getter("adderOutputTip"));
        cin.setToolTip(Strings.getter("adderCarryInTip"));
        cout.setToolTip(Strings.getter("adderCarryOutTip"));

        if (pinMode) {
            Port signSel = new Port(-30, 20, Port.INPUT, BitWidth.ONE);
            signSel.setToolTip(Strings.getter("adderSignSelTip")); // añade en Strings
            instance.setPorts(new Port[]{ in0, in1, out, cin, cout, signSel });
        } else {
            instance.setPorts(new Port[]{ in0, in1, out, cin, cout });
        }

        instance.fireInvalidated();
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

        boolean signed = decideSigned(state, modeOpt, width, a, b);
        Value[] outs = computeSum(width, a, b, c_in, signed);

        // propagate them
        int delay = (width.getWidth() + 2) * PER_DELAY;
        state.setPort(OUT,   outs[0], delay);
        state.setPort(C_OUT, outs[1], delay);
    }

    private static boolean decideSigned(InstanceState st, AttributeOption modeOpt, BitWidth w,
                                        Value a, Value b) {
        if (modeOpt == MODE_SIGNED)  return true;
        if (modeOpt == MODE_UNSIGNED) return false;

        if (modeOpt == MODE_PIN) {
            try {
                Value sel = st.getPort(SIGN_SEL);
                return sel == Value.TRUE;  // 1 => signed; 0/NC/X => unsigned
            } catch (IndexOutOfBoundsException ex) {
                return false;
            }
        }

        // AUTO: heurística por MSB(A) o MSB(B)
        int width = w.getWidth();
        int ai = a.isFullyDefined() ? a.toIntValue() : 0;
        int bi = b.isFullyDefined() ? b.toIntValue() : 0;
        return msbSet(ai, width) || msbSet(bi, width);
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

        AttributeOption m = painter.getAttributeValue(SIGN_MODE);
        String coutLbl = (m == MODE_SIGNED) ? "ovf" : (m == MODE_UNSIGNED ? "c out" : "c/ovf");
        painter.drawPort(C_OUT, coutLbl, Direction.SOUTH);

        if (pinModeEnabled(painter.getInstance())) {
            painter.drawPort(SIGN_SEL);
        }

        Location loc = painter.getLocation();
        int x = loc.getX();
        int y = loc.getY();
        GraphicsUtil.switchToWidth(g, 2);
        g.setColor(Color.BLACK);
        g.drawLine(x - 15, y, x - 5, y);
        g.drawLine(x - 10, y - 5, x - 10, y + 5);
        GraphicsUtil.switchToWidth(g, 1);

        try {
            String tag = (m == MODE_SIGNED) ? "S" : (m == MODE_UNSIGNED ? "U" : (m == MODE_PIN ? "P" : "A"));
            g.setColor(Color.DARK_GRAY);
            g.drawString(tag, x - 30, y + 5);
        } catch (Exception ignore) {}
    }

    /* ====================== Núcleo ====================== */
    static Value[] computeSum(BitWidth width, Value a, Value b, Value c_in, boolean signed) {
        int w = width.getWidth();
        if (c_in == Value.UNKNOWN || c_in == Value.NIL) c_in = Value.FALSE;

        // Camino rápido: totalmente definidos
        if (a.isFullyDefined() && b.isFullyDefined() && c_in.isFullyDefined()) {
            int ai = a.toIntValue();
            int bi = b.toIntValue();
            int ci = c_in.toIntValue() & 1;

            long mask = (w >= 64) ? -1L : ((1L << w) - 1L);

            if (!signed) {
                // ===== Unsigned: carry-out real
                long av = ((long) ai) & mask;
                long bv = ((long) bi) & mask;
                long sum = av + bv + ci;
                int  out = (int) (sum & mask);
                Value carry = (((sum >>> w) & 1L) != 0) ? Value.TRUE : Value.FALSE;
                return new Value[]{ Value.createKnown(width, out), carry };
            } else {
                // ===== Signed: overflow (two's complement)
                long min = -(1L << (w - 1));
                long max =  (1L << (w - 1)) - 1;

                long av = signExtend(ai, w);
                long bv = signExtend(bi, w);
                long sum = av + bv + ci;

                int  out = (int) (sum & mask);
                boolean ovf = (sum < min) || (sum > max);
                Value ovfBit = ovf ? Value.TRUE : Value.FALSE;
                return new Value[]{ Value.createKnown(width, out), ovfBit };
            }
        }

        // Camino bit-a-bit (UNKNOWN/ERROR) — carry clásico
        Value[] bits = new Value[w];
        Value carry = c_in;
        for (int i = 0; i < w; i++) {
            if (carry == Value.ERROR) {
                bits[i] = Value.ERROR;
            } else if (carry == Value.UNKNOWN) {
                bits[i] = Value.UNKNOWN;
            } else {
                Value ab = a.get(i);
                Value bb = b.get(i);
                if (ab == Value.ERROR || bb == Value.ERROR) {
                    bits[i] = Value.ERROR; carry = Value.ERROR;
                } else if (ab == Value.UNKNOWN || bb == Value.UNKNOWN) {
                    bits[i] = Value.UNKNOWN; carry = Value.UNKNOWN;
                } else {
                    int s = (ab == Value.TRUE ? 1 : 0)
                            + (bb == Value.TRUE ? 1 : 0)
                            + (carry == Value.TRUE ? 1 : 0);
                    bits[i] = ((s & 1) == 1) ? Value.TRUE : Value.FALSE;
                    carry = (s >= 2) ? Value.TRUE : Value.FALSE;
                }
            }
        }
        return new Value[]{ Value.create(bits), carry };
    }

    /* ====================== helpers ====================== */
    private static boolean msbSet(int val, int w) {
        if (w <= 0) return false;
        int bit = 1 << (w - 1);
        return (val & bit) != 0;
    }

    private static long signExtend(int val, int w) {
        long mask = (w >= 64) ? -1L : ((1L << w) - 1L);
        long u = ((long) val) & mask;
        long sign = 1L << (w - 1);
        return (u ^ sign) - sign;
    }
}
