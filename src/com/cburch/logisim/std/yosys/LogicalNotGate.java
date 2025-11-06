package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;

public class LogicalNotGate extends InstanceFactory {
    private static final int DELAY = 2;

    // Puertos
    static final int A   = 0;
    static final int OUT = 1;

    public LogicalNotGate() {
        super("Logical NOT Gate", Strings.getter("logicNotGateComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.FACING, StdAttr.WIDTH },
                new Object[]  { Direction.EAST,  BitWidth.create(8) }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setFacingAttribute(StdAttr.FACING);
        setIconName("logicnot.gif"); // opcional
    }

    @Override
    public Bounds getOffsetBounds(AttributeSet attrs) {
        Direction dir = attrs.getValue(StdAttr.FACING);
        return Bounds.create(-20, -10, 20, 20).rotate(Direction.EAST, dir, 0, 0);
    }

    @Override
    protected void configureNewInstance(Instance inst) {
        inst.addAttributeListener();
        updatePorts(inst);
    }

    @Override
    protected void instanceAttributeChanged(Instance inst, Attribute<?> attr) {
        if (attr == StdAttr.FACING || attr == StdAttr.WIDTH) {
            inst.recomputeBounds();
            updatePorts(inst);
        }
    }

    private void updatePorts(Instance inst) {
        Direction dir = inst.getAttributeValue(StdAttr.FACING);
        BitWidth   w  = inst.getAttributeValue(StdAttr.WIDTH);

        Location aLoc, yLoc;
        if (dir == Direction.WEST) {
            aLoc = Location.create( 20,  0);
            yLoc = Location.create(  0,  0);
        } else if (dir == Direction.NORTH) {
            aLoc = Location.create( 0,  20);
            yLoc = Location.create( 0,   0);
        } else if (dir == Direction.SOUTH) {
            aLoc = Location.create( 0, -20);
            yLoc = Location.create( 0,   0);
        } else { // EAST
            aLoc = Location.create(-20,  0);
            yLoc = Location.create(  0,  0);
        }

        Port pa = new Port(aLoc.getX(), aLoc.getY(), Port.INPUT,  w);
        Port py = new Port(yLoc.getX(), yLoc.getY(), Port.OUTPUT, BitWidth.ONE);

        pa.setToolTip(Strings.getter("logicNotATip"));
        py.setToolTip(Strings.getter("logicNotYTip"));

        inst.setPorts(new Port[]{ pa, py });
        inst.fireInvalidated();
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth w = state.getAttributeValue(StdAttr.WIDTH);
        Value a    = state.getPort(A);

        Tri isZero = isZeroTri(w, a);
        Value out;
        switch (isZero) {
            case ERROR -> out = Value.ERROR;
            case TRUE  -> out = Value.TRUE;    // A == 0  → !A = 1
            case FALSE -> out = Value.FALSE;   // A != 0  → !A = 0
            default    -> out = Value.UNKNOWN; // indeterminado
        }
        state.setPort(OUT, out, DELAY);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();

        g.setColor(Color.GRAY);
        painter.drawPort(A);
        painter.drawPort(OUT);

        Bounds b = painter.getBounds();
        int cx = b.getX() + b.getWidth()  / 2;
        int cy = b.getY() + b.getHeight() / 2;

        g.setColor(Color.BLACK);
        GraphicsUtil.drawCenteredText(g, "!", cx, cy);
    }

    /* ===== Helpers tri-estado para “A == 0” ===== */
    private enum Tri { TRUE, FALSE, UNKNOWN, ERROR }

    private static Tri isZeroTri(BitWidth w, Value v) {
        if (v.isErrorValue()) return Tri.ERROR;
        int W = Math.max(1, w.getWidth());

        if (v.isFullyDefined()) {
            long mask = (W >= 63) ? -1L : ((1L << W) - 1);
            long iv = ((long) v.toIntValue()) & mask;
            return (iv == 0) ? Tri.TRUE : Tri.FALSE;
        }

        // Bit a bit: si hay TRUE en algún bit, ya no es cero → FALSE.
        // Si todos son FALSE → TRUE. Si hay UNKNOWN pero ningún TRUE → UNKNOWN.
        boolean sawUnknown = false;
        Value[] bits = v.getAll();
        boolean allZero = true;
        for (Value bit : bits) {
            if (bit == Value.ERROR)   return Tri.ERROR;
            if (bit == Value.TRUE)    return Tri.FALSE;
            if (bit == Value.UNKNOWN) sawUnknown = true;
            // bit FALSE no cambia allZero
        }
        if (allZero) return Tri.TRUE;
        return sawUnknown ? Tri.UNKNOWN : Tri.FALSE;
    }
}
