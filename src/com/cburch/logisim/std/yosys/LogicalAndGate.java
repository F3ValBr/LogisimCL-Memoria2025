package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;

public class LogicalAndGate extends InstanceFactory {
    private static final int DELAY = 3;

    // Puertos (índices estables)
    static final int A   = 0;
    static final int B   = 1;
    static final int OUT = 2;

    public LogicalAndGate() {
        super("Logical AND Gate", Strings.getter("logicAndGateComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.FACING, StdAttr.WIDTH },
                new Object[]  { Direction.EAST,  BitWidth.create(8) }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setFacingAttribute(StdAttr.FACING);
        setIconName("logicand.gif"); // opcional
    }

    @Override
    public Bounds getOffsetBounds(AttributeSet attrs) {
        Direction dir = attrs.getValue(StdAttr.FACING);
        // Tamaño fijo 40x40, estilo aritméticos
        return Bounds.create(-40, -20, 40, 40).rotate(Direction.EAST, dir, 0, 0);
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
        BitWidth w    = inst.getAttributeValue(StdAttr.WIDTH);

        // Posiciones tipo rectángulo estándar
        Location aLoc, bLoc, yLoc;
        if (dir == Direction.WEST) {
            aLoc = Location.create( 40, -10);
            bLoc = Location.create( 40,  10);
            yLoc = Location.create(  0,   0);
        } else if (dir == Direction.NORTH) {
            aLoc = Location.create(-10,  40);
            bLoc = Location.create( 10,  40);
            yLoc = Location.create(  0,   0);
        } else if (dir == Direction.SOUTH) {
            aLoc = Location.create(-10, -40);
            bLoc = Location.create( 10, -40);
            yLoc = Location.create(  0,   0);
        } else { // EAST
            aLoc = Location.create(-40, -10);
            bLoc = Location.create(-40,  10);
            yLoc = Location.create(  0,   0);
        }

        Port pa = new Port(aLoc.getX(), aLoc.getY(), Port.INPUT,  w);
        Port pb = new Port(bLoc.getX(), bLoc.getY(), Port.INPUT,  w);
        Port py = new Port(yLoc.getX(), yLoc.getY(), Port.OUTPUT, BitWidth.ONE);

        pa.setToolTip(Strings.getter("logicAndATip"));
        pb.setToolTip(Strings.getter("logicAndBTip"));
        py.setToolTip(Strings.getter("logicAndYTip"));

        inst.setPorts(new Port[]{ pa, pb, py });
        inst.fireInvalidated();
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth w = state.getAttributeValue(StdAttr.WIDTH);

        Value a = state.getPort(A);
        Value b = state.getPort(B);

        // reduce lógico: (A != 0) && (B != 0), con 4 valores
        Tri nzA = nonZeroTri(w, a);
        Tri nzB = nonZeroTri(w, b);

        Value out;
        if (nzA == Tri.ERROR || nzB == Tri.ERROR)      out = Value.ERROR;
        else if (nzA == Tri.FALSE || nzB == Tri.FALSE) out = Value.FALSE;
        else if (nzA == Tri.TRUE && nzB == Tri.TRUE)   out = Value.TRUE;
        else                                           out = Value.UNKNOWN;

        state.setPort(OUT, out, DELAY);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        painter.drawBounds();

        g.setColor(Color.GRAY);
        painter.drawPort(A);
        painter.drawPort(B);
        painter.drawPort(OUT);

        Bounds bds = painter.getBounds();
        int cx = bds.getX() + bds.getWidth()  / 2;
        int cy = bds.getY() + bds.getHeight() / 2;

        g.setColor(Color.BLACK);
        GraphicsUtil.drawCenteredText(g, "&&", cx, cy);
    }

    // ====== Lógica de reducción “no-cero” con 3 valores + error ======
    private enum Tri { TRUE, FALSE, UNKNOWN, ERROR }

    private static Tri nonZeroTri(BitWidth w, Value v) {
        if (v.isErrorValue()) return Tri.ERROR;
        if (v.isFullyDefined()) {
            long mask = (w.getWidth() >= 63) ? -1L : ((1L << w.getWidth()) - 1);
            long iv = ((long) v.toIntValue()) & mask;
            return (iv != 0) ? Tri.TRUE : Tri.FALSE;
        }

        boolean sawUnknown = false;
        Value[] bits = v.getAll();
        for (Value bit : bits) {
            if (bit == Value.ERROR)   return Tri.ERROR;
            if (bit == Value.TRUE)    return Tri.TRUE;
            if (bit == Value.UNKNOWN) sawUnknown = true;
        }
        return sawUnknown ? Tri.UNKNOWN : Tri.FALSE;
    }
}
