package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;
import java.math.BigInteger;

/** Exponent: OUT = (BASE ** EXP) mod 2^W
 *  - SIGN_MODE: Unsigned / Signed / Auto (Auto mira MSB(BASE))
 *  - EXP se interpreta SIEMPRE como UNSIGNED
 */
public class Exponent extends InstanceFactory {
    static final int PER_DELAY = 1;

    static final int IN0 = 0; // BASE
    static final int IN1 = 1; // EXP (unsigned)
    static final int OUT = 2; // RESULT

    // ===== Modo de signo =====
    public static final AttributeOption MODE_UNSIGNED
            = new AttributeOption("unsigned", "unsigned", Strings.getter("unsignedOption"));
    public static final AttributeOption MODE_SIGNED
            = new AttributeOption("signed", "signed",  Strings.getter("signedOption"));
    public static final AttributeOption MODE_AUTO
            = new AttributeOption("auto", "auto", Strings.getter("autoOption"));
    public static final Attribute<AttributeOption> SIGN_MODE =
            Attributes.forOption("signMode", Strings.getter("arithSignMode"),
                    new AttributeOption[]{ MODE_UNSIGNED, MODE_SIGNED, MODE_AUTO });

    public Exponent() {
        super("Exponent", Strings.getter("exponentComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.WIDTH, SIGN_MODE },
                new Object[]  { BitWidth.create(8), MODE_AUTO }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("exponent.gif"); // si no tienes icono, no pasa nada

        Port[] ps = new Port[3];
        ps[IN0] = new Port(-40, -10, Port.INPUT,  StdAttr.WIDTH);
        ps[IN1] = new Port(-40,  10, Port.INPUT,  StdAttr.WIDTH);
        ps[OUT] = new Port(  0,   0, Port.OUTPUT, StdAttr.WIDTH);
        ps[IN0].setToolTip(Strings.getter("exponentBaseTip"));
        ps[IN1].setToolTip(Strings.getter("exponentExponentTip"));
        ps[OUT].setToolTip(Strings.getter("exponentOutputTip"));
        setPorts(ps);
    }

    @Override
    protected void configureNewInstance(Instance instance) {
        super.configureNewInstance(instance);
        instance.getAttributeSet().addAttributeListener(new AttributeListener() {
            @Override public void attributeValueChanged(AttributeEvent e) {
                Attribute<?> a = e.getAttribute();
                if (a == SIGN_MODE) {
                    instance.fireInvalidated();
                } else if (a == StdAttr.WIDTH) {
                    instance.recomputeBounds();
                    instance.fireInvalidated();
                }
            }
            @Override public void attributeListChanged(AttributeEvent e) { }
        });
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth w = state.getAttributeValue(StdAttr.WIDTH);
        AttributeOption signOpt = state.getAttributeValue(SIGN_MODE);

        Value base = state.getPort(IN0);
        Value exp  = state.getPort(IN1);

        Value out = computePow(w, base, exp, signOpt);

        int delay = Math.max(1, (w.getWidth() + 2) * PER_DELAY);
        state.setPort(OUT, out, delay);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();

        g.setColor(Color.GRAY);
        painter.drawPort(IN0);
        painter.drawPort(IN1);
        painter.drawPort(OUT);

        // Dibujo simple de “^”
        Location loc = painter.getLocation();
        int x = loc.getX(), y = loc.getY();
        GraphicsUtil.switchToWidth(g, 2);
        g.setColor(Color.BLACK);
        g.drawLine(x - 15, y, x - 10, y - 5);
        g.drawLine(x - 10, y - 5, x - 5, y);
        GraphicsUtil.switchToWidth(g, 1);

        // Marca de modo U/S/A
        try {
            AttributeOption m = painter.getAttributeValue(SIGN_MODE);
            String tag = (m == MODE_SIGNED) ? "S" : (m == MODE_UNSIGNED ? "U" : "A");
            g.setColor(Color.DARK_GRAY);
            g.drawString(tag, x - 30, y + 5);
        } catch (Exception ignore) { }
    }

    /* =================== Núcleo =================== */
    static Value computePow(BitWidth width, Value base, Value exp, AttributeOption signOpt) {
        int w = width.getWidth();

        // Errores/unknowns
        if (base.isErrorValue() || exp.isErrorValue())
            return Value.createError(width);
        if (!(base.isFullyDefined() && exp.isFullyDefined()))
            return Value.createUnknown(width);

        // Módulo 2^W y máscara (como BigInteger)
        BigInteger MOD   = BigInteger.ONE.shiftLeft(w);          // 2^W
        BigInteger MASK  = MOD.subtract(BigInteger.ONE);         // 2^W - 1

        // EXP siempre UNSIGNED (Yosys estándar)
        BigInteger e = bigUnsigned(exp, w);

        // Decide modo para la BASE
        boolean signed;
        if (signOpt == MODE_SIGNED) {
            signed = true;
        } else if (signOpt == MODE_UNSIGNED) {
            signed = false;
        } else { // AUTO: signed si MSB(base)==1
            signed = base.getAll()[w - 1] == Value.TRUE;
        }

        // Lee la base según el modo
        BigInteger a = signed ? bigSigned(base, w) : bigUnsigned(base, w);

        // Casos rápidos
        if (e.signum() == 0) {
            // x^0 = 1
            return Value.createKnown(width, 1);
        }
        // Nota: con wrap mod 2^W, 0^e = 0 para e>0
        if (a.and(MASK).signum() == 0) {
            return Value.createKnown(width, 0);
        }

        // Exponenciación rápida (square & multiply) con wrap 2^W
        BigInteger res     = BigInteger.ONE;
        BigInteger baseAcc = a.and(MASK); // reducir a W bits
        BigInteger ee      = e;

        while (ee.signum() > 0) {
            if (ee.testBit(0)) {
                res = res.multiply(baseAcc).and(MASK); // == mod 2^W
            }
            ee = ee.shiftRight(1);
            if (ee.signum() > 0) {
                baseAcc = baseAcc.multiply(baseAcc).and(MASK);
            }
        }

        // Value.createKnown(BitWidth,int) ya recorta a W bits internamente
        return Value.createKnown(width, res.intValue());
    }

    /** Lee Value (definido) como BigInteger UNSIGNED (bits tal cual). */
    private static BigInteger bigUnsigned(Value v, int w) {
        Value[] bits = v.getAll();
        BigInteger acc = BigInteger.ZERO;
        for (int i = 0; i < w; i++) {
            if (bits[i] == Value.TRUE) {
                acc = acc.setBit(i);
            }
        }
        return acc;
    }

    /** Lee Value (definido) como BigInteger SIGNED en two's complement, rango [-2^(w-1), 2^(w-1)-1]. */
    private static BigInteger bigSigned(Value v, int w) {
        BigInteger u = bigUnsigned(v, w);
        BigInteger signBit = BigInteger.ONE.shiftLeft(w - 1);
        if (u.testBit(w - 1)) {
            // valor negativo: u - 2^W
            return u.subtract(BigInteger.ONE.shiftLeft(w));
        } else {
            return u;
        }
    }



    private static long mulWrap(long a, long b, int w) {
        // Multiplica y hace wrap 2^w
        if (w >= 63) {
            long mask = mask(w);
            long prod = a * b;
            return prod & mask;
        } else {
            long mask = (1L << w) - 1L;
            long prod = (a & mask) * (b & mask);
            return prod & mask;
        }
    }

    /* =================== helpers =================== */
    private static long mask(int w) {
        return (w >= 64) ? -1L : ((1L << w) - 1L);
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

