package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.tools.key.KeyConfigurationEvent;
import com.cburch.logisim.tools.key.KeyConfigurationResult;
import com.cburch.logisim.tools.key.KeyConfigurator;

import java.awt.*;

public class DynamicShifter extends InstanceFactory {
    private static final int DELAY = 3;

    // ---- Opciones de modo ----
    static final AttributeOption MODE_SHIFT  =
            new AttributeOption("shift",  Strings.getter("shiftMode"));   // $shift
    static final AttributeOption MODE_SHIFTX =
            new AttributeOption("shiftx", Strings.getter("shiftxMode"));  // $shiftx

    static final Attribute<AttributeOption> ATTR_MODE =
            Attributes.forOption("mode", Strings.getter("dynamicShifterMode"),
                    new AttributeOption[]{ MODE_SHIFT, MODE_SHIFTX });

    // ---- Signo de entradas ----
    static final Attribute<Boolean> ATTR_A_SIGNED =
            Attributes.forBoolean("aSigned", Strings.getter("shifterASigned"));
    static final Attribute<Boolean> ATTR_B_SIGNED =
            Attributes.forBoolean("bSigned", Strings.getter("shifterBSigned"));

    // ---- Anchos de entradas y salida ----
    static final Attribute<BitWidth> ATTR_AWIDTH =
            Attributes.forBitWidth("aWidth", Strings.getter("shifterAWidth"));
    static final Attribute<BitWidth> ATTR_BWIDTH =
            Attributes.forBitWidth("bWidth", Strings.getter("shifterBWidth")); // ej. 3..5 bits
    static final Attribute<BitWidth> ATTR_YWIDTH =
            Attributes.forBitWidth("yWidth", Strings.getter("shifterYWidth"));

    // Puertos
    static final int IN_A = 0;
    static final int IN_B = 1;
    static final int OUT_Y = 2;

    /** Permite combinar varios KeyConfigurator sin importar el fork de Logisim. */
    static final class MultiKeyConfigurator implements KeyConfigurator {
        private final KeyConfigurator[] ks;

        MultiKeyConfigurator(KeyConfigurator... ks) {
            this.ks = (ks == null) ? new KeyConfigurator[0] : ks.clone();
        }

        @Override public KeyConfigurator clone() {
            KeyConfigurator[] copies = new KeyConfigurator[ks.length];
            for (int i = 0; i < ks.length; i++) copies[i] = (ks[i] == null) ? null : ks[i].clone();
            return new MultiKeyConfigurator(copies);
        }

        @Override public KeyConfigurationResult keyEventReceived(KeyConfigurationEvent event) {
            KeyConfigurationResult last = null;
            for (KeyConfigurator k : ks) {
                if (k == null) continue;
                KeyConfigurationResult r = k.keyEventReceived(event);
                if (r == null) continue;
                if (!isNoAction(r)) return r;
                last = r;
            }
            return last;
        }

        private static boolean isNoAction(KeyConfigurationResult r) {
            try { var m = r.getClass().getMethod("isNoAction"); Object v = m.invoke(r); if (v instanceof Boolean b) return b; }
            catch (Exception ignore) { }
            try { var f = r.getClass().getField("NONE"); Object none = f.get(null); return r.equals(none); }
            catch (Exception ignore) { }
            return false;
        }
    }

    public DynamicShifter() {
        super("Dynamic Shifter", Strings.getter("dynamicShifterComponent"));
        setAttributes(
                new Attribute[]{
                        ATTR_AWIDTH,     // ancho de A
                        ATTR_BWIDTH,     // ancho de B
                        ATTR_YWIDTH,     // ancho de Y
                        ATTR_MODE,       // shift | shiftx
                        ATTR_A_SIGNED,
                        ATTR_B_SIGNED
                },
                new Object[]{
                        BitWidth.create(8),
                        BitWidth.create(3),
                        BitWidth.create(8),
                        MODE_SHIFT,
                        Boolean.FALSE,
                        Boolean.FALSE
                }
        );

        setKeyConfigurator(new MultiKeyConfigurator(
                new BitWidthConfigurator(DynamicShifter.ATTR_AWIDTH),
                new BitWidthConfigurator(DynamicShifter.ATTR_BWIDTH),
                new BitWidthConfigurator(DynamicShifter.ATTR_YWIDTH)
        ));

        setOffsetBounds(Bounds.create(-40, -20, 40, 40));
        setIconName("dynamicShifter.gif");
    }

    @Override
    protected void configureNewInstance(Instance inst) {
        inst.addAttributeListener();
        configurePorts(inst);
    }

    @Override
    protected void instanceAttributeChanged(Instance inst, Attribute<?> attr) {
        if (attr == ATTR_MODE || attr == ATTR_AWIDTH || attr == ATTR_BWIDTH || attr == ATTR_YWIDTH) {
            inst.recomputeBounds();
            configurePorts(inst);
        }
    }

    private static int bw(AttributeSet a, Attribute<BitWidth> key, int dflt) {
        BitWidth bw = a.getValue(key);
        return bw == null ? dflt : Math.max(1, bw.getWidth());
    }

    private void configurePorts(Instance instance) {
        AttributeSet as = instance.getAttributeSet();

        int aW = bw(as, ATTR_AWIDTH, 8);
        int bW = bw(as, ATTR_BWIDTH, 3);
        int yW = bw(as, ATTR_YWIDTH, 8);

        Port[] ps = new Port[3];
        ps[IN_A]  = new Port(-40, -10, Port.INPUT,   aW);
        ps[IN_B]  = new Port(-40,  10, Port.INPUT,   bW);
        ps[OUT_Y] = new Port(  0,   0, Port.OUTPUT,  yW);

        ps[IN_A].setToolTip(Strings.getter("dynamicShifterInputA"));
        ps[IN_B].setToolTip(Strings.getter("dynamicShifterInputB"));
        ps[OUT_Y].setToolTip(Strings.getter("dynamicShifterOutputY"));

        instance.setPorts(ps);
    }

    @Override
    public void propagate(InstanceState s) {
        AttributeOption mode = s.getAttributeValue(ATTR_MODE);
        boolean aSigned = Boolean.TRUE.equals(s.getAttributeValue(ATTR_A_SIGNED));
        boolean bSigned = Boolean.TRUE.equals(s.getAttributeValue(ATTR_B_SIGNED));

        int aW = bw(s.getAttributeSet(), ATTR_AWIDTH, 8);
        int bW = bw(s.getAttributeSet(), ATTR_BWIDTH, 3);
        int yW = bw(s.getAttributeSet(), ATTR_YWIDTH, 8);

        Value vA = s.getPort(IN_A);
        Value vB = s.getPort(IN_B);

        Value out = (mode == MODE_SHIFTX)
                ? evalShiftX(vA, vB, aW, yW, bSigned, bW)
                : evalShift(vA, vB, aW, aSigned, bSigned, yW, bW);

        int delay = Math.max(aW, yW) * DELAY;
        s.setPort(OUT_Y, out, delay);
    }

    /** --- $shiftx según Yosys ---
     * Desplazamiento lógico variable con salida de ancho independiente.
     * Si el desplazamiento apunta fuera de A, se rellena con UNKNOWN.
     */
    private static Value evalShiftX(Value vA, Value vB, int aW, int yW, boolean bSigned, int bW) {
        BitWidth outW = BitWidth.create(yW);
        if (vA == null || vB == null) return Value.createError(outW);

        if (!vB.isFullyDefined()) return Value.createUnknown(outW);

        int bVal = vB.toIntValue();
        if (!bSigned) {
            int mask = (bW >= 31) ? -1 : ((1 << bW) - 1);
            bVal &= mask;
        }
        Value[] out = new Value[yW];

        if (!vA.isFullyDefined()) {
            Value[] aBits = vA.getAll();
            for (int i = 0; i < yW; i++) {
                int idx = bVal + i;
                out[i] = (idx < 0 || idx >= aW) ? Value.UNKNOWN : aBits[idx];
            }
        } else {
            int aInt = vA.toIntValue();
            for (int i = 0; i < yW; i++) {
                int idx = bVal + i;
                if (idx < 0 || idx >= aW) out[i] = Value.UNKNOWN;
                else out[i] = ((aInt >>> idx) & 1) == 0 ? Value.FALSE : Value.TRUE;
            }
        }
        return Value.create(out);
    }

    /** --- $shift según Yosys ---
     * Y =  (B_SIGNED && B<0) ?  (A << -B)  :  (A_SIGNED ? A >>>_arith B : A >>>_logic B)
     * La salida tiene ancho yW independiente.
     */
    private static Value evalShift(Value vA, Value vB, int aW,
                                   boolean aSigned, boolean bSigned,
                                   int yW, int bW) {
        BitWidth outW = BitWidth.create(yW);
        if (vA == null || vB == null) return Value.createError(outW);
        if (!vB.isFullyDefined())      return Value.createError(outW);

        // 1) amount según signo de B
        int rawAmt = vB.toIntValue();
        int amt = bSigned ? (int) signExtend(rawAmt, bW)
                : (rawAmt & (bW >= 31 ? -1 : ((1 << bW) - 1)));

        // 2) Caso A no totalmente definida: construimos Y directamente de tamaño yW
        if (!vA.isFullyDefined()) {
            Value[] aBits = vA.getAll();     // LSB..MSB, longitud aW
            Value[] yBits = new Value[yW];

            if (bSigned && amt < 0) {
                // LEFT shift por k = -amt, relleno 0
                int k = Math.min(-amt, Integer.MAX_VALUE);
                for (int i = 0; i < yW; i++) {
                    int src = i - k;
                    yBits[i] = (src >= 0 && src < aW) ? aBits[src] : Value.FALSE;
                }
            } else {
                // RIGHT shift por k = max(amt,0), arith si A signed, lógico si no
                int k = Math.max(amt, 0);
                Value fill = aSigned ? aBits[Math.max(0, aW - 1)] : Value.FALSE;
                for (int i = 0; i < yW; i++) {
                    int src = i + k;
                    yBits[i] = (src >= 0 && src < aW) ? aBits[src] : fill;
                }
            }
            return Value.create(yBits);
        }

        // 3) Caso A totalmente definida: calcula en ancho "amplio" y luego recorta a yW
        int xi = vA.toIntValue();
        long aUnsigned = unsigned(xi, aW);
        long aSigned64 = signExtend(xi, aW);

        long res;
        if (bSigned && amt < 0) {
            // LEFT shift lógico por k = -amt (sin enmascarar a aW)
            int k = Math.min(-amt, 63); // evitar overflow de shift
            res = (aUnsigned << k);
        } else {
            // RIGHT shift por k = max(amt,0)
            int k = Math.min(Math.max(amt, 0), 63);
            if (aSigned) {
                res = (aSigned64 >> k);   // aritmético (sign-extended)
            } else {
                res = (aUnsigned >>> k);  // lógico
            }
        }

        // 4) Recortar a yW (la salida)
        long maskY = yW >= 63 ? -1L : ((1L << yW) - 1L);
        int outVal = (int) (res & maskY);
        return Value.createKnown(outW, outVal);
    }

    /* ==== helpers numéricos ==== */
    private static long mask(int w) {
        return (w >= 63) ? -1L : ((1L << w) - 1L);
    }
    private static long unsigned(int val, int w) {
        return ((long) val) & mask(w);
    }
    private static long signExtend(int val, int w) {
        long u = ((long) val) & mask(w);
        long sign = 1L << (w - 1);
        return (u ^ sign) - sign; // extiende signo a 64 bits
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();
        painter.drawPorts();

        Location loc = painter.getLocation();
        int x = loc.getX() - 18, y = loc.getY();
        AttributeOption mode = painter.getAttributeValue(ATTR_MODE);

        g.setColor(Color.BLACK);
        if (mode == MODE_SHIFTX) {
            g.drawString("[+:]", x - 6, y + 4);
        } else {
            g.fillRect(x + 2, y - 4, 12, 3);
            drawArrow(g, x - 2, y - 3, 4);
            g.fillRect(x - 2, y + 4, 12, 3);
            drawArrow(g, x + 14, y + 5, -4);
        }
    }

    private void drawArrow(Graphics g, int x, int y, int d) {
        int[] px = { x + d, x, x + d };
        int[] py = { y + d, y, y - d };
        g.fillPolygon(px, py, 3);
    }
}
