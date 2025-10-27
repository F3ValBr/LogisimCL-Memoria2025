/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.arith;

import java.awt.Color;
import java.awt.Graphics;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

public class Subtractor extends InstanceFactory {
    static final int IN0   = 0; // A (minuend)
    static final int IN1   = 1; // B (subtrahend)
    static final int OUT   = 2; // Y = A - B - B_IN
    static final int B_IN  = 3; // borrow in
    static final int B_OUT = 4; // borrow out (unsigned) / overflow (signed)
    static final int SIGN_SEL = 5; // dinámico: sólo existe en MODE_PIN

    // ===== Modo de signo =====
    public static final AttributeOption MODE_UNSIGNED
            = new AttributeOption("unsigned", "unsigned", Strings.getter("unsignedOption"));
    public static final AttributeOption MODE_SIGNED
            = new AttributeOption("signed", "signed",  Strings.getter("signedOption"));
    public static final AttributeOption MODE_PIN
            = new AttributeOption("pin", "pin", Strings.getter("pinOption")); // agrega en Strings
    public static final AttributeOption MODE_AUTO
            = new AttributeOption("auto", "auto", Strings.getter("autoOption"));

    public static final Attribute<AttributeOption> SIGN_MODE =
            Attributes.forOption("signMode", Strings.getter("arithSignMode"),
                    new AttributeOption[]{ MODE_UNSIGNED, MODE_SIGNED, MODE_PIN, MODE_AUTO });

    public Subtractor() {
        super("Subtractor", Strings.getter("subtractorComponent"));
        setAttributes(
                new Attribute[] { StdAttr.WIDTH, SIGN_MODE },
                new Object[]  { BitWidth.create(8), MODE_UNSIGNED }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("subtractor.gif");
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
        return instance.getAttributeValue(SIGN_MODE) == MODE_PIN;
    }

    private void updatePorts(Instance instance) {
        BitWidth w = instance.getAttributeValue(StdAttr.WIDTH);
        boolean pinMode = pinModeEnabled(instance);

        Port in0   = new Port(-40, -10, Port.INPUT,  w);
        Port in1   = new Port(-40,  10, Port.INPUT,  w);
        Port out   = new Port(  0,   0, Port.OUTPUT, w);
        Port bin   = new Port(-20, -20, Port.INPUT,  1);
        Port bout  = new Port(-20,  20, Port.OUTPUT, 1);

        in0.setToolTip(Strings.getter("subtractorMinuendTip"));
        in1.setToolTip(Strings.getter("subtractorSubtrahendTip"));
        out.setToolTip(Strings.getter("subtractorOutputTip"));
        bin.setToolTip(Strings.getter("subtractorBorrowInTip"));
        bout.setToolTip(Strings.getter("subtractorBorrowOutTip")); // en signed será overflow

        if (pinMode) {
            Port signSel = new Port(-30, 20, Port.INPUT, BitWidth.ONE);
            signSel.setToolTip(Strings.getter("subtractorSignSelTip")); // añade en Strings
            instance.setPorts(new Port[]{ in0, in1, out, bin, bout, signSel });
        } else {
            instance.setPorts(new Port[]{ in0, in1, out, bin, bout });
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
        Value b_in = state.getPort(B_IN);
        if (b_in == Value.UNKNOWN || b_in == Value.NIL) b_in = Value.FALSE;

        // Decide modo con/sin signo (igual criterio que en Adder)
        boolean signed = decideSigned(state, modeOpt, width, a, b);

        // A - B - B_IN  =  A + (~B) + (~B_IN)
        Value[] sum = Adder.computeSum(width, a, b.not(), b_in.not(), signed);

        // propagate them
        // OUT siempre es la suma anterior
        int delay = (width.getWidth() + 4) * Adder.PER_DELAY;
        state.setPort(OUT, sum[0], delay);

        // B_OUT depende del modo:
        //  - Unsigned: BorrowOut = NOT(CarryOut)
        //  - Signed:   Overflow  = sum[1] (Adder devuelve ovf en modo signed)
        Value bout = signed ? sum[1] : sum[1].not();
        state.setPort(B_OUT, bout, delay);
    }

    private static boolean decideSigned(InstanceState st, AttributeOption modeOpt, BitWidth w,
                                        Value a, Value b) {
        if (modeOpt == MODE_SIGNED)  return true;
        if (modeOpt == MODE_UNSIGNED) return false;

        if (modeOpt == MODE_PIN) {
            try {
                Value sel = st.getPort(SIGN_SEL);
                return sel == Value.TRUE; // 1 => signed; 0/NC/X => unsigned
            } catch (IndexOutOfBoundsException ex) {
                return false;
            }
        }

        // AUTO: signed si MSB(A) o MSB(B)
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
        painter.drawPort(B_IN,  "b in",  Direction.NORTH);

        // Etiqueta dinámica para B_OUT: "b/ovf"
        AttributeOption m = painter.getAttributeValue(SIGN_MODE);
        String boutLbl = (m == MODE_SIGNED) ? "ovf" : (m == MODE_UNSIGNED ? "b out" : "b/ovf");
        painter.drawPort(B_OUT, boutLbl, Direction.SOUTH);

        if (pinModeEnabled(painter.getInstance())) {
            painter.drawPort(SIGN_SEL);
        }

        Location loc = painter.getLocation();
        int x = loc.getX();
        int y = loc.getY();
        GraphicsUtil.switchToWidth(g, 2);
        g.setColor(Color.BLACK);
        g.drawLine(x - 15, y, x - 5, y); // símbolo "−"
        GraphicsUtil.switchToWidth(g, 1);

        // Marca de modo: U/S/P/A
        try {
            String tag = (m == MODE_SIGNED) ? "S" : (m == MODE_UNSIGNED ? "U" : (m == MODE_PIN ? "P" : "A"));
            g.setColor(Color.DARK_GRAY);
            g.drawString(tag, x - 30, y + 5);
        } catch (Exception ignore) {}
    }

    /* ===== Helpers ===== */
    private static boolean msbSet(int val, int w) {
        if (w <= 0) return false;
        int bit = 1 << (w - 1);
        return (val & bit) != 0;
    }
}
