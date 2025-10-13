package com.cburch.logisim.std.wiring;

import com.cburch.logisim.comp.TextField;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.Color;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.util.*;

import static com.cburch.logisim.data.Direction.*;

/**
 * BitLabeledTunnel (Logisim 2.7.1)
 * - Puerto multibit único (INPUT u OUTPUT según atributo).
 * - Por dentro, cada bit se mapea a "0", "1", "x" o un label (String).
 * - Los labels se conectan por un backplane lógico compartido por Circuit.
 * Atributos:
 *  - StdAttr.WIDTH
 *  - StdAttr.LABEL
 *  - StdAttr.FACING
 *  - BitLabeledTunnel.BIT_SPECS (String CSV)
 *  - BitLabeledTunnel.ATTR_OUTPUT (Boolean)
 */
public class BitLabeledTunnel extends InstanceFactory {

    public static final BitLabeledTunnel FACTORY = new BitLabeledTunnel();

    // Atributos nuevos
    public static final Attribute<String> BIT_SPECS =
            Attributes.forString("bitSpecs", Strings.getter("bitSpecsAttr"));
    public static final Attribute<Boolean> ATTR_OUTPUT =
            Attributes.forBoolean("output", Strings.getter("outputAttr"));

    // Geometría estilo Tunnel
    static final int MARGIN = 3;
    static final int ARROW_MIN_WIDTH = 16;

    public BitLabeledTunnel() {
        super("BitLabeledTunnel", Strings.getter("BLTunnelComponent"));
        setIconName("bltunnel.gif");
        setFacingAttribute(StdAttr.FACING);
        setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
    }

    @Override
    public AttributeSet createAttributeSet() {
        BitLabeledTunnelAttributes at = new BitLabeledTunnelAttributes();
        at.setValue(StdAttr.WIDTH, BitWidth.create(1));
        at.setValue(StdAttr.LABEL, "");
        at.setValue(StdAttr.FACING, WEST);
        at.setValue(BIT_SPECS, "");
        at.setValue(ATTR_OUTPUT, Boolean.FALSE);
        return at;
    }

    /* ==================== Pintado ==================== */

    @Override
    public Bounds getOffsetBounds(AttributeSet attrsBase) {
        TunnelAttributes attrs = (TunnelAttributes) attrsBase;
        Bounds bds = attrs.getOffsetBounds();
        if (bds != null) return bds;

        int ht = attrs.getFont().getSize();
        int wd = ht * attrs.getLabel().length() / 2;
        bds = computeBounds(attrs, wd, ht, null, attrs.getLabel());
        attrs.setOffsetBounds(bds);
        return bds;
    }

    @Override
    public void paintGhost(InstancePainter painter) {
        TunnelAttributes attrs = (TunnelAttributes) painter.getAttributeSet();
        Direction facing = attrs.getFacing();
        String label = attrs.getLabel();

        Graphics g = painter.getGraphics();
        g.setFont(attrs.getFont());
        FontMetrics fm = g.getFontMetrics();

        // bounds basados en el texto
        Bounds bds = computeBounds(attrs, fm.stringWidth(label),
                fm.getAscent() + fm.getDescent(), g, label);
        if (attrs.setOffsetBounds(bds)) {
            Instance inst = painter.getInstance();
            if (inst != null) inst.recomputeBounds();
        }

        final int x0 = bds.getX();
        final int y0 = bds.getY();
        final int w  = bds.getWidth();
        final int h  = bds.getHeight();

        // Geometría
        int headDepth  = Math.max(10, (int)Math.round(h * 0.30));
        int notchDepth = -Math.max(8,  (int)Math.round(h * 0.30));

        // Limitar para que no se crucen
        int maxHead = Math.max(8, w - Math.abs(notchDepth) - 10);
        if (headDepth > maxHead) headDepth = maxHead;

        int[] xp, yp;
        int L = x0; // Left
        int T = y0; // Top
        int R = x0 + w; // Right
        int B = y0 + h; // Bottom

        if (facing.equals(EAST)) {
            int M = (T + B) / 2;

            xp = new int[] {L + notchDepth, R - headDepth, R, R - headDepth, L + notchDepth, L};
            yp = new int[] {T, T, M, B, B, M};
        } else if (facing.equals(WEST)) {
            int M = (T + B) / 2;

            xp = new int[] {R - notchDepth, L + headDepth, L, L + headDepth, R - notchDepth, R};
            yp = new int[] {T, T, M, B, B, M};
        } else if (facing.equals(NORTH)) {
            int M = (L + R) / 2;

            xp = new int[] {L, L, M, R, R, (L + R) / 2};
            yp = new int[] {B - notchDepth, T + headDepth, T, T + headDepth, B - notchDepth, B};
        } else {
            int M = (L + R) / 2;

            xp = new int[] {L, L, M, R, R, (L + R) / 2};
            yp = new int[] {T + notchDepth, B - headDepth, B, B - headDepth, T + notchDepth, T};
        }

        // === Recalcular bounds según el polígono (para que la selección lo cubra) ===
        int minX = xp[0], maxX = xp[0], minY = yp[0], maxY = yp[0];
        for (int i = 1; i < xp.length; i++) {
            if (xp[i] < minX) minX = xp[i];
            if (xp[i] > maxX) maxX = xp[i];
            if (yp[i] < minY) minY = yp[i];
            if (yp[i] > maxY) maxY = yp[i];
        }
        Bounds polyB = Bounds.create(minX, minY, maxX - minX, maxY - minY).expand(MARGIN).add(0, 0);

        // Si cambió, avisa a Logisim y usa el nuevo
        if (attrs.setOffsetBounds(polyB)) {
            Instance inst = painter.getInstance();
            if (inst != null) inst.recomputeBounds();
        }

        // Colores diferenciados por modo OUTPUT/INPUT
        boolean isOutput = Boolean.TRUE.equals(attrs.getValue(BitLabeledTunnel.ATTR_OUTPUT));
        Color border = isOutput ? Color.BLUE.darker() : Color.GREEN.darker();

        g.setColor(border);
        GraphicsUtil.switchToWidth(g, 2);
        g.drawPolygon(xp, yp, xp.length);
    }


    @Override
    public void paintInstance(InstancePainter painter) {
        Location loc = painter.getLocation();
        int x = loc.getX();
        int y = loc.getY();
        Graphics g = painter.getGraphics();

        // --- Dibujo del túnel (con translate local) ---
        g.translate(x, y);
        g.setColor(Color.BLACK);
        paintGhost(painter);
        g.translate(-x, -y);

        // --- Puertos ---
        painter.drawPorts();

        // --- Overlays de advertencia ---
        AttributeSet atts = painter.getAttributeSet();
        BitWidth bw = atts.getValue(StdAttr.WIDTH);
        int w = Math.max(1, bw.getWidth());

        String csv = atts.getValue(BIT_SPECS);
        String csvSafe = (csv == null) ? "" : csv;

        // Conteo REAL de tokens para detectar mismatch (antes de pad/trunc)
        int tokenCount = 0;
        if (!csvSafe.trim().isEmpty()) {
            String[] toksRaw = csvSafe.split(",");
            for (String t : toksRaw) {
                if (t != null && !t.trim().isEmpty()) tokenCount++;
            }
        }

        // Specs ya normalizados para otras comprobaciones visuales
        List<String> specsVis = parseSpecs(csvSafe, w);

        boolean isOutput = Boolean.TRUE.equals(atts.getValue(ATTR_OUTPUT));

        // Advertencia 1: constantes en modo INPUT
        boolean hasConst = false;
        for (String s : specsVis) {
            if ("0".equals(s) || "1".equals(s)) { hasConst = true; break; }
        }

        // Advertencia 2: longitud CSV != WIDTH
        boolean lenMismatch = (tokenCount != w);

        // Bounds locales → absolutos
        Bounds b = getOffsetBounds(atts);
        int absX = x + b.getX();
        int absY = y + b.getY();
        int bbW  = b.getWidth();

        // Dibuja “!” rojo (constantes en INPUT)
        if (!isOutput && hasConst) {
            Color old = g.getColor();
            g.setColor(Color.RED);
            g.fillOval(absX - 7, absY - 7, 14, 14);
            g.setColor(Color.WHITE);
            g.drawString("!", absX - 7 + 4, absY - 7 + 12);
            g.setColor(old);
        }

        // Dibuja “?” naranja (mismatch de longitud CSV vs WIDTH)
        if (lenMismatch) {
            Color old = g.getColor();
            // esquina superior derecha del polígono del túnel
            int cx = absX + bbW - 7;
            int cy = absY - 7;
            g.setColor(new Color(255, 140, 0)); // naranja
            g.fillOval(cx, cy, 14, 14);
            g.setColor(Color.WHITE);
            g.drawString("?", cx + 3, cy + 12);
            g.setColor(old);
        }
    }


    /* ============== Configuración de instancia/atributos ============== */

    private static void configureLabel(Instance instance) {
        TunnelAttributes attrs = (TunnelAttributes) instance.getAttributeSet();
        Location loc = instance.getLocation();
        instance.setTextField(StdAttr.LABEL, StdAttr.LABEL_FONT,
                loc.getX() + attrs.getLabelX(), loc.getY() + attrs.getLabelY(),
                attrs.getLabelHAlign(), attrs.getLabelVAlign());
    }

    private static void reconfigurePorts(Instance instance) {
        boolean out = Boolean.TRUE.equals(instance.getAttributeSet().getValue(ATTR_OUTPUT));
        String kind = out ? Port.OUTPUT : Port.INPUT; // ✅ tipo correcto
        instance.setPorts(new Port[] { new Port(0, 0, kind, StdAttr.WIDTH) });
    }

    @Override
    protected void configureNewInstance(Instance instance) {
        instance.addAttributeListener();
        reconfigurePorts(instance);
        configureLabel(instance);
    }

    @Override
    protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        if (attr == StdAttr.FACING) {
            configureLabel(instance);
            instance.recomputeBounds();
        } else if (attr == StdAttr.LABEL || attr == StdAttr.LABEL_FONT) {
            instance.recomputeBounds();
        } else if (attr == StdAttr.WIDTH || attr == BIT_SPECS || attr == ATTR_OUTPUT) {
            reconfigurePorts(instance);
            instance.fireInvalidated();
        }
    }

    /* ===================== Simulación ===================== */

    @Override
    public void propagate(InstanceState state) {
        // Los hilos ya fueron unidos por CircuitWires.connectBitLabeledTunnels
        // No conducir manualmente para evitar lazos.
    }

    /* ==================== Helpers de dibujo ==================== */

    private Bounds computeBounds(TunnelAttributes attrs, int textWidth,
                                 int textHeight, Graphics g, String label) {
        int x = attrs.getLabelX();
        int y = attrs.getLabelY();
        int halign = attrs.getLabelHAlign();
        int valign = attrs.getLabelVAlign();

        int minDim = ARROW_MIN_WIDTH - 2 * MARGIN;
        int bw = Math.max(minDim, textWidth);
        int bh = Math.max(minDim, textHeight);
        int bx;
        int by;
        bx = switch (halign) {
            case TextField.H_LEFT -> x;
            case TextField.H_RIGHT -> x - bw;
            default -> x - (bw / 2);
        };
        by = switch (valign) {
            case TextField.V_TOP -> y;
            case TextField.V_BOTTOM -> y - bh;
            default -> y - (bh / 2);
        };

        if (g != null) {
            GraphicsUtil.drawText(g, label, bx + bw / 2, by + bh / 2,
                    GraphicsUtil.H_CENTER, GraphicsUtil.V_CENTER_OVERALL);
        }

        return Bounds.create(bx, by, bw, bh).expand(MARGIN).add(0, 0);
    }


    /* ==================== Helpers CSV/constantes ==================== */

    private static List<String> parseSpecs(String csv, int width) {
        List<String> out = new ArrayList<>(width);
        if (csv == null || csv.trim().isEmpty()) {
            for (int i = 0; i < width; i++) out.add("x");
            return out;
        }
        String[] toks = csv.split(",");
        for (String t : toks) out.add(t.trim());
        if (out.size() < width) {
            while (out.size() < width) out.add("x");
        } else if (out.size() > width) {
            out = out.subList(0, width);
        }
        return out;
    }
}
