package com.cburch.logisim.std.yosys;


import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;

import static com.cburch.logisim.data.Direction.*;

public class BitwiseMultiplexer extends InstanceFactory {
    static final int A  = 0;
    static final int B  = 1;
    static final int S  = 2;
    static final int Y  = 3;

    public BitwiseMultiplexer() {
        super("Bitwise Multiplexer", Strings.getter("bwmuxComponent"));
        setAttributes(
                new Attribute[]{ StdAttr.FACING, StdAttr.WIDTH },
                new Object[]   { EAST, BitWidth.create(8) }
        );
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
        setOffsetBounds(Bounds.create(-50, -20, 60, 40));
        setIconName("bwmultiplexer.gif"); // opcional, usa cualquier icono existente
        setFacingAttribute(StdAttr.FACING);
    }

    @Override
    public void propagate(InstanceState state) {
        BitWidth w = state.getAttributeValue(StdAttr.WIDTH);
        Value a = state.getPort(A);
        Value b = state.getPort(B);
        Value s = state.getPort(S);

        Value out = computeY(w, a, b, s);
        // Delay lineal con el ancho (muy barato comparado a macro):
        int delay = Math.max(1, w.getWidth());
        state.setPort(Y, out, delay);
    }

    @Override
    public void paintGhost(InstancePainter painter) {
        Direction facing = painter.getAttributeValue(StdAttr.FACING);
        YosysComponent.drawTrapezoid(painter.getGraphics(), painter.getBounds(), facing,10);
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        Bounds bds = painter.getBounds();
        Direction facing = painter.getAttributeValue(StdAttr.FACING);

        // --- 1) Dibuja "stub" corto del pin S hacia el cuerpo, como Multiplexer ---
        GraphicsUtil.switchToWidth(g, 3);
        boolean vertical = (facing != Direction.NORTH && facing != SOUTH);
        // Para bwmux no tenemos ATTR_SELECT_LOC; decidimos el stub segun orientación:
        int selDx = vertical ? 0 : -1;
        int selDy = vertical ? (facing == EAST || facing == WEST ? +1 : 0) : 0;

        Location selLoc = painter.getInstance().getPortLocation(S);
        if (painter.getShowState()) g.setColor(painter.getPort(S).getColor());
        // Longitud del stub: igual al caso inputs==2 del Multiplexer (pequeño)
        g.drawLine(selLoc.getX() - 2 * selDx, selLoc.getY() - 2 * selDy, selLoc.getX(), selLoc.getY());
        GraphicsUtil.switchToWidth(g, 1);

        // --- 2) Círculo de selección (misma lógica que Multiplexer) ---
        drawSelectCircleInside(g, bds, selLoc);

        // --- 3) Trapezoide y rótulo centrado ---
        g.setColor(Color.BLACK);
        YosysComponent.drawTrapezoid(g, bds, facing, /* garganta */ 10);
        GraphicsUtil.drawCenteredText(g, "BWMUX",
                bds.getX() + bds.getWidth() / 2,
                bds.getY() + bds.getHeight() / 2);

        // --- 4) Puertos (Logisim se encarga de dibujar sus "dots" y labels si hay) ---
        painter.drawPorts();

        int xA;
        int yA;
        int halign;

        if (facing == Direction.WEST) {
            xA = bds.getX() + bds.getWidth() - 3;
            yA = bds.getY() + 15;
            halign = GraphicsUtil.H_RIGHT;
        } else if (facing == Direction.NORTH) {
            xA = bds.getX() + 10;
            yA = bds.getY() + bds.getHeight() - 2;
            halign = GraphicsUtil.H_CENTER;
        } else if (facing == Direction.SOUTH) {
            xA = bds.getX() + 10;
            yA = bds.getY() + 12;
            halign = GraphicsUtil.H_CENTER;
        } else { // EAST
            xA = bds.getX() + 3;
            yA = bds.getY() + 15;
            halign = GraphicsUtil.H_LEFT;
        }

        g.setColor(Color.GRAY);
        GraphicsUtil.drawText(g, "A", xA, yA, halign, GraphicsUtil.V_BASELINE);
    }

    @Override
    public Bounds getOffsetBounds(AttributeSet attrs) {
        // Caja base estilo MUX (dos datos + 1 select + 1 salida)
        Bounds base = Bounds.create(-60, -20, 60, 40);
        Direction dir = attrs.getValue(StdAttr.FACING);
        // Rota igual que el Multiplexer
        return base.rotate(EAST, dir, 0, 0);
    }

    @Override
    public boolean contains(Location loc, AttributeSet attrs) {
        Direction facing = attrs.getValue(StdAttr.FACING);
        return YosysComponent.contains(loc, getOffsetBounds(attrs), facing);
    }

    @Override
    protected void configureNewInstance(Instance instance) {
        instance.addAttributeListener();
        updatePorts(instance);
    }

    @Override
    protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        if (attr == StdAttr.FACING) {
            instance.recomputeBounds();
            updatePorts(instance);
        } else if (attr == StdAttr.WIDTH) {
            updatePorts(instance);
        }
    }

    private void updatePorts(Instance instance) {
        Direction dir = instance.getAttributeValue(StdAttr.FACING);
        BitWidth w = instance.getAttributeValue(StdAttr.WIDTH);

        Port[] ps = new Port[4];
        Location a, b, s, y;

        if (dir == Direction.WEST) {
            // salida a la izquierda, entradas a la derecha
            a = Location.create( 60, -10);
            b = Location.create( 60,  10);
            s = Location.create( 40,  20);
            y = Location.create(  0,   0);
        } else if (dir == Direction.NORTH) {
            // salida arriba, entradas abajo
            a = Location.create(-10,  60);
            b = Location.create( 10,  60);
            s = Location.create(-20,  40);
            y = Location.create(  0,   0);
        } else if (dir == Direction.SOUTH) {
            // salida abajo, entradas arriba
            a = Location.create(-10, -60);
            b = Location.create( 10, -60);
            s = Location.create(-20, -40);
            y = Location.create(  0,   0);
        } else { // EAST (por defecto)
            a = Location.create(-60, -10);
            b = Location.create(-60,  10);
            s = Location.create(-40,  20);
            y = Location.create(  0,   0);
        }

        ps[A] = new Port(a.getX(), a.getY(), Port.INPUT,  w);
        ps[B] = new Port(b.getX(), b.getY(), Port.INPUT,  w);
        ps[S] = new Port(s.getX(), s.getY(), Port.INPUT,  w); // selector bitwise (mismo ancho)
        ps[Y] = new Port(y.getX(), y.getY(), Port.OUTPUT, w);

        ps[A].setToolTip(Strings.getter("bwmuxATip"));
        ps[B].setToolTip(Strings.getter("bwmuxBTip"));
        ps[S].setToolTip(Strings.getter("bwmuxSelTip"));
        ps[Y].setToolTip(Strings.getter("bwmuxYTip"));

        instance.setPorts(ps);
    }

    /* ================= Núcleo ================= */

    // Regla por bit: Y[i] = S[i] ? B[i] : A[i]
    // Propagación robusta:
    // - Si S[i] = ERROR → Y[i] = ERROR
    // - Si S[i] = UNKNOWN → si A[i]==B[i] → ese valor; si difieren → UNKNOWN
    // - Si S[i] = 0 → Y[i] = A[i]
    // - Si S[i] = 1 → Y[i] = B[i]
    static Value computeY(BitWidth w, Value a, Value b, Value s) {
        int width = w.getWidth();

        // Rutas rápidas si todo está definido:
        if (a.isFullyDefined() && b.isFullyDefined() && s.isFullyDefined()) {
            // entero: (A & ~S) | (B & S)
            long mask = (width >= 63) ? -1L : ((1L << width) - 1);
            long av = ((long) a.toIntValue()) & mask;
            long bv = ((long) b.toIntValue()) & mask;
            long sv = ((long) s.toIntValue()) & mask;
            long yv = (av & ~sv) | (bv & sv);
            return Value.createKnown(w, (int)(yv & mask));
        }

        // Camino bit-a-bit (UNKNOWN/ERROR correctos)
        Value[] aa = a.getAll();
        Value[] bb = b.getAll();
        Value[] ss = s.getAll();
        Value[] yy = new Value[width];

        for (int i = 0; i < width; i++) {
            Value si = ss[i];
            Value ai = aa[i];
            Value bi = bb[i];

            if (si == Value.ERROR || ai == Value.ERROR || bi == Value.ERROR) {
                yy[i] = Value.ERROR;
            } else if (si == Value.UNKNOWN) {
                // selector indeterminado: si A[i]==B[i], ese valor; si no, UNKNOWN
                if (ai == bi) yy[i] = ai;
                else yy[i] = Value.UNKNOWN;
            } else if (si == Value.FALSE) {
                yy[i] = ai; // hereda unknown si ai es UNKNOWN
            } else { // si == TRUE
                yy[i] = bi;
            }
        }
        return Value.create(yy);
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
