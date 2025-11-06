package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.*;

import com.cburch.logisim.instance.*;
import com.cburch.logisim.std.plexers.Plexers;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.tools.key.JoinedConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;

public class BinaryMultiplexer extends InstanceFactory {
    private static final int DELAY = 3;

    // Puertos (índices estables)
    static final int A  = 0;
    static final int A_X= 1; // sólo existe si totalBits>32
    static final int S  = 2;
    static final int Y  = 3;

    public BinaryMultiplexer() {
        super("Binary Multiplexer", Strings.getter("bmuxComponent"));
        setAttributes(
                new Attribute[] { StdAttr.FACING, Plexers.ATTR_SELECT, StdAttr.WIDTH },
                new Object[]    { Direction.EAST, BitWidth.ONE, BitWidth.create(8) }
        );
        setFacingAttribute(StdAttr.FACING);
        setKeyConfigurator(JoinedConfigurator.create(
                new BitWidthConfigurator(Plexers.ATTR_SELECT, 1, 5, 0),
                new BitWidthConfigurator(StdAttr.WIDTH)));
        setIconName("bmultiplexer.gif");
    }

    @Override
    public Bounds getOffsetBounds(AttributeSet attrs) {
        Direction dir = attrs.getValue(StdAttr.FACING);
        Bounds base = Bounds.create(-50, -20, 50, 40);
        return base.rotate(Direction.EAST, dir, 0, 0);
    }

    @Override
    protected void configureNewInstance(Instance inst) {
        inst.addAttributeListener();
        updatePorts(inst);
    }

    @Override
    protected void instanceAttributeChanged(Instance inst, Attribute<?> attr) {
        if (attr == StdAttr.FACING || attr == Plexers.ATTR_SELECT || attr == StdAttr.WIDTH) {
            inst.recomputeBounds();
            updatePorts(inst);
        }
    }

    private void updatePorts(Instance inst) {
        Direction dir = inst.getAttributeValue(StdAttr.FACING);
        BitWidth w    = inst.getAttributeValue(StdAttr.WIDTH);       // WIDTH por slice
        BitWidth sW   = inst.getAttributeValue(Plexers.ATTR_SELECT); // S_WIDTH
        int inputs    = 1 << Math.max(0, sW.getWidth());
        int totalBits = inputs * w.getWidth();

        // Cap físico: 64 bits → A_main hasta 32, A_x resto hasta 32
        int mainLen  = Math.min(32, totalBits);
        int extraLen = Math.max(0, Math.min(32, totalBits - mainLen));
        boolean hasAX = extraLen > 0;

        // Geometría parecida al Multiplexer
        Port aMain, aX = null, s, y;

        // A y A_X a la izquierda; S cerca del borde (círculo), Y al centro
        Location aMainLoc, aXLoc, sLoc, yLoc;
        // colocaciones simples tipo MUX 2-entradas vs. multi
        if (dir == Direction.WEST) {
            aMainLoc = Location.create(50, -10);
            aXLoc    = Location.create(50,  10);
            sLoc     = Location.create(30,  20);
            yLoc     = Location.create( 0,   0);
        } else if (dir == Direction.NORTH) {
            aMainLoc = Location.create(-10, 50);
            aXLoc    = Location.create( 10, 50);
            sLoc     = Location.create(-20, 30);
            yLoc     = Location.create(  0,  0);
        } else if (dir == Direction.SOUTH) {
            aMainLoc = Location.create(-10, -50);
            aXLoc    = Location.create( 10, -50);
            sLoc     = Location.create(-20, -30);
            yLoc     = Location.create(  0,   0);
        } else { // EAST (default)
            aMainLoc = Location.create(-50, -10);
            aXLoc    = Location.create(-50,  10);
            sLoc     = Location.create(-30,  20);
            yLoc     = Location.create(  0,   0);
        }

        aMain = new Port(aMainLoc.getX(), aMainLoc.getY(), Port.INPUT,  BitWidth.create(mainLen));
        if (hasAX) {
            aX = new Port(aXLoc.getX(), aXLoc.getY(), Port.INPUT, BitWidth.create(extraLen));
        }
        s = new Port(sLoc.getX(), sLoc.getY(), Port.INPUT, sW.getWidth());
        y = new Port(yLoc.getX(), yLoc.getY(), Port.OUTPUT, w.getWidth());

        aMain.setToolTip(Strings.getter("bmuxATip"));
        if (hasAX) aX.setToolTip(Strings.getter("bmuxAXTip"));
        s.setToolTip(Strings.getter("bmuxSelTip"));
        y.setToolTip(Strings.getter("bmuxYTip"));

        if (hasAX) {
            inst.setPorts(new Port[]{ aMain, aX, s, y });
        } else {
            inst.setPorts(new Port[]{ aMain,     s, y });
        }
        inst.fireInvalidated();
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth w  = state.getAttributeValue(StdAttr.WIDTH);       // WIDTH por slice
        BitWidth sW = state.getAttributeValue(Plexers.ATTR_SELECT); // S_WIDTH

        int W        = Math.max(1, w.getWidth());
        int sWidth   = Math.max(0, sW.getWidth());
        int inputs   = 1 << sWidth;
        int totalBits= inputs * W;

        // Cap físico: 64 → main hasta 32, extra hasta 32
        int mainLen  = Math.min(32, totalBits);
        int extraLen = Math.max(0, Math.min(32, totalBits - mainLen));
        boolean hasAX = extraLen > 0;

        // Puertos coherentes con updatePorts:
        Value aMain = state.getPort(A);
        Value aX    = hasAX ? state.getPort(A_X) : Value.createKnown(BitWidth.create(0), 0);
        int   S_INDEX = hasAX ? S : A_X;      // índice del selector
        int   Y_INDEX = hasAX ? Y : S;   // índice de la salida
        Value sel   = state.getPort(S_INDEX);

        Value out;
        int capBits     = mainLen + extraLen;              // ≤ 64
        int physSlices  = Math.max(1, capBits / W);

        // Manejo de errores/unknowns (similar al MUX original)
        if (aMain.isErrorValue() || (hasAX && aX.isErrorValue())) {
            out = Value.createError(w);
        } else if (sel.isErrorValue()) {
            out = Value.createError(w);
        } else if (!sel.isFullyDefined()) {
            out = Value.createUnknown(w);
        } else {
            int k = sel.toIntValue(); // 0..(inputs-1)
            int kWrapped = (physSlices > 0) ? (k % physSlices) : 0;
            out = slicePackedBus(aMain, mainLen, aX, extraLen, W, kWrapped, w);
        }

        state.setPort(Y_INDEX, out, DELAY);
    }

    private static Value slicePackedBus(Value aMain, int mainLen,
                                        Value aX,    int extraLen,
                                        int W, int kSlice, BitWidth widthOut) {
        // Concat A_total = [ aMain (LSBs 0..mainLen-1) || aX (mainLen..mainLen+extraLen-1) ]
        Value[] m = aMain.getAll();
        Value[] x = (extraLen > 0) ? aX.getAll() : new Value[0];
        Value[] y = new Value[W];

        int base = kSlice * W;
        int cap  = mainLen + extraLen; // ≤ 64

        for (int i = 0; i < W; i++) {
            int bit = base + i;
            if (cap > 0) bit %= cap; // wrap seguro
            if (bit < mainLen) {
                y[i] = m[bit];
            } else {
                int j = bit - mainLen;
                y[i] = (j >= 0 && j < x.length) ? x[j] : Value.FALSE;
            }
        }
        return Value.create(y);
    }

    @Override
    public void paintGhost(InstancePainter painter) {
        Direction facing = painter.getAttributeValue(StdAttr.FACING);
        YosysComponent.drawTrapezoid(painter.getGraphics(), painter.getBounds(), facing, 10);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        Bounds bds = painter.getBounds();
        Direction facing = painter.getAttributeValue(StdAttr.FACING);

        BitWidth w  = painter.getAttributeValue(StdAttr.WIDTH);
        BitWidth sW = painter.getAttributeValue(Plexers.ATTR_SELECT);

        final int S_INDEX = getSIndex(w, sW);

        // Dibujar el alambrito corto del select (como Multiplexer)
        GraphicsUtil.switchToWidth(g, 3);
        boolean vertical = facing != Direction.NORTH && facing != Direction.SOUTH;
        int selMult = 1;
        int dx = vertical ? 0 : -selMult;
        int dy = vertical ? selMult : 0;

        Location sLoc = painter.getInstance().getPortLocation(S_INDEX);
        if (painter.getShowState()) {
            g.setColor(painter.getPort(S_INDEX).getColor());
        }
        g.drawLine(sLoc.getX() - 2 * dx, sLoc.getY() - 2 * dy, sLoc.getX(), sLoc.getY());
        GraphicsUtil.switchToWidth(g, 1);

        // Circulito del select
        drawSelectCircleInside(g, bds, sLoc);

        // Trapecio y rotulación
        g.setColor(Color.BLACK);
        YosysComponent.drawTrapezoid(g, bds, facing, 10);
        GraphicsUtil.drawCenteredText(g, "BMUX",
                bds.getX() + bds.getWidth() / 2,
                bds.getY() + bds.getHeight() / 2);

        // Etiqueta “A” dentro del cuerpo (ajustada por orientación)
        int x0, y0, halign;
        if (facing == Direction.WEST) {
            x0 = bds.getX() + bds.getWidth() - 3;
            y0 = bds.getY() + 15;
            halign = GraphicsUtil.H_RIGHT;
        } else if (facing == Direction.NORTH) {
            x0 = bds.getX() + 10;
            y0 = bds.getY() + bds.getHeight() - 2;
            halign = GraphicsUtil.H_CENTER;
        } else if (facing == Direction.SOUTH) {
            x0 = bds.getX() + 10;
            y0 = bds.getY() + 12;
            halign = GraphicsUtil.H_CENTER;
        } else { // EAST
            x0 = bds.getX() + 3;
            y0 = bds.getY() + 15;
            halign = GraphicsUtil.H_LEFT;
        }
        g.setColor(Color.GRAY);
        GraphicsUtil.drawText(g, "A", x0, y0, halign, GraphicsUtil.V_BASELINE);

        painter.drawPorts();
    }

    private static int getSIndex(BitWidth w, BitWidth sW) {
        int W        = Math.max(1, w.getWidth());
        int sWidth   = Math.max(0, sW.getWidth());
        int inputs   = 1 << sWidth;
        int totalBits= inputs * W;

        // Derivar si existe A_X (cap 64 → 32+32)
        int mainLen  = Math.min(32, totalBits);
        int extraLen = Math.max(0, Math.min(32, totalBits - mainLen));
        boolean hasAX = extraLen > 0;

        // Índices de puertos (coherentes con updatePorts/propagate)
        return hasAX ? S : A_X;
    }

    private static void drawSelectCircleInside(Graphics g, Bounds bds, Location sel) {
        // Igual criterio que el MUX: desplazamiento pequeño hacia adentro
        int locDelta = Math.max(bds.getHeight(), bds.getWidth()) <= 50 ? 10 : 8;

        int midX = bds.getX() + bds.getWidth()  / 2;
        int midY = bds.getY() + bds.getHeight() / 2;

        int dx = 0, dy = 0;

        // Detecta el lado: si el pin está más a la izquierda/derecha/arriba/abajo del centro
        // y empuja el círculo hacia ADENTRO del trapezoide.
        if (Math.abs(sel.getX() - midX) > Math.abs(sel.getY() - midY)) {
            // Más desplazado horizontalmente → izquierda o derecha
            if (sel.getX() < midX) dx = locDelta;      // pin a la IZQUIERDA → mueve círculo DERECHA
            else                   dx = -locDelta;      // pin a la DERECHA → mueve círculo IZQUIERDA
        } else {
            // Más desplazado verticalmente → arriba o abajo
            if (sel.getY() < midY) dy = locDelta;      // pin ARRIBA → mueve círculo ABAJO
            else                   dy = -locDelta;      // pin ABAJO → mueve círculo ARRIBA
        }

        int cx = sel.getX() + dx;
        int cy = sel.getY() + dy;

        g.setColor(Color.LIGHT_GRAY);
        g.fillOval(cx - 3, cy - 3, 6, 6);
    }
}
