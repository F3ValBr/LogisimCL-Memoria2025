package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.BitLabeledTunnel;
import com.cburch.logisim.std.wiring.Tunnel;
import com.cburch.logisim.verilog.file.importer.routing.GridRouter;
import com.cburch.logisim.verilog.file.importer.routing.MstPlanner;
import com.cburch.logisim.verilog.file.importer.routing.RouterUtils;
import com.cburch.logisim.verilog.std.Strings;

import java.awt.*;
import java.util.*;
import java.util.List;

import static com.cburch.logisim.verilog.file.importer.VerilogJsonImporter.GRID;

/**
 * ReWrite BitLabeledTunnels as direct wires when possible.
 * - Group tunnels by (normalized label, normalized+sorted specs).
 * - Check isolation of relevant tokens (N..., names).
 * - Plan MST and route each edge with A* Manhattan avoiding components.
 * - If whole group routes, add wires and remove group's tunnels.
 */
public final class BitLabeledTunnelRewriter {

    private BitLabeledTunnelRewriter() {}

    /** Rewrite BitLabeledTunnels in the given circuit.
     * @param proj Project (for actions).
     * @param circ Circuit to rewrite.
     * @param g Graphics context (for measuring components).
     */
    public static void rewrite(Project proj, Circuit circ, Graphics g) {
        if (proj == null || circ == null) return;

        // 1) Recolectar túneles
        List<TunnelInfo> all = collectBlt(circ);
        if (all.isEmpty()) return;

        // 2) Agrupar por (labelNorm, tokensNorm)
        Map<GroupKey, List<TunnelInfo>> groups = groupByLabelAndSpecs(all);

        // 3) Filtrar grupos aislados y con 2 o más miembros
        List<Map.Entry<GroupKey, List<TunnelInfo>>> rewriteEntries = new ArrayList<>();
        for (Map.Entry<GroupKey, List<TunnelInfo>> e : groups.entrySet()) {
            if (e.getValue().size() < 2) continue;
            if (isGroupIsolated(e.getKey(), groups)) rewriteEntries.add(e);
        }
        if (rewriteEntries.isEmpty()) return;

        // 4) Intentar reescribir cada grupo de forma independiente
        for (Map.Entry<GroupKey, List<TunnelInfo>> entry : rewriteEntries) {
            try {
                replaceGroupWith(proj, circ, g, entry.getValue());
            } catch (Throwable t) {
                // No abortar proceso completo por un grupo
                t.printStackTrace();
            }
        }
    }

    /** Replaces the given group of tunnels with routed wires using GridRouter.
     * If routing any edge fails, no changes are applied.
     * @param proj Project (for actions).
     * @param circ Circuit to modify.
     * @param g Graphics context (for measuring components).
     * @param grp List of TunnelInfo in the same group.
     */
    private static void replaceGroupWith(Project proj,
                                         Circuit circ,
                                         Graphics g,
                                         List<TunnelInfo> grp) {
        if (grp == null || grp.size() < 2) return;

        // Evitar grupos gigantes que disparan combinatoria
        final int MAX_GROUP_SIZE = 24;
        if (grp.size() > MAX_GROUP_SIZE) return;

        // 1) Preparar bocas y facings
        List<Location> mouths = new ArrayList<>(grp.size());
        List<Direction> facings = new ArrayList<>(grp.size());
        for (TunnelInfo ti : grp) {
            mouths.add(ti.mouth());
            Direction f = Direction.EAST;
            try {
                AttributeSet as = ti.comp().getAttributeSet();
                Direction v = (as != null) ? as.getValue(StdAttr.FACING) : null;
                if (v != null) f = v;
            } catch (Throwable ignore) { }
            facings.add(f);
        }

        // 2) MST por Manhattan
        List<int[]> edges = MstPlanner.buildMstEdges(mouths);
        if (edges.isEmpty()) return;

        // 3) Obstáculos: componentes + wires existentes
        final int WIRE_MARGIN = 1; // margen alrededor de los wires para que no toquen
        List<Bounds> obstacles = RouterUtils.collectComponentBounds(circ, g, grp);
        obstacles.addAll(RouterUtils.collectWireBounds(circ, WIRE_MARGIN));

        // índice de “reservas” (celdas) para penalizar rutas posteriores
        Set<Long> reserved = new HashSet<>();

        GridRouter router = new GridRouter(
                GRID,
                /*soft*/3, /*hard*/5,
                /*costNear*/12, /*costReserved*/6,
                obstacles, reserved
        )
                .withMaxExpansions(40_000)   // límite duro de nodos expandidos
                .withMaxQueue(50_000)        // límite duro de tamaño cola
                .withMaxMillis(1200);        // watchdog por ruta (ms)

        // 4) Planificación (agregamos obstáculos dinámicos por cada ruta ya trazada)
        List<Wire> planned = new ArrayList<>(edges.size() * 4);
        boolean ok = true;

        for (int[] e : edges) {
            int i = e[0], j = e[1];
            Location mi = mouths.get(i);
            Location mj = mouths.get(j);

            Location si = RouterUtils.launchPad(mi, facings.get(i), GRID, 1);
            Location tj = RouterUtils.launchPad(mj, facings.get(j), GRID, 1);

            // Fallback rápido: intenta HV y VH recto evitando OBSTÁCULOS (incluyen wires)
            List<Location> poly = RouterUtils.tryManhattanClear(si, tj, obstacles, /*clearHard*/5);
            if (poly == null) {
                // A* acotado con bbox alrededor de si–tj y obstáculos actuales (incl. wires)
                poly = router.route(si, tj);
            }
            if (poly == null || poly.size() < 2) { ok = false; break; }

            // Reservar la ruta para penalizar futuras y añadir obstáculos dinámicos
            poly = RouterUtils.simplifyPolyline(poly, obstacles, /*clearHard*/5);
            RouterUtils.markReservedPath(reserved, poly, GRID);
            obstacles.addAll(RouterUtils.polylineAsWireBounds(poly, WIRE_MARGIN));

            // También los “puentes” desde la boca hasta el pad
            obstacles.addAll(RouterUtils.segmentAsWireBounds(mi, si, WIRE_MARGIN));
            obstacles.addAll(RouterUtils.segmentAsWireBounds(tj, mj, WIRE_MARGIN));

            // Conectar: boca->pad, polyline, pad->boca
            planned.add(Wire.create(mi, si));
            for (int k = 0; k + 1 < poly.size(); k++) {
                planned.add(Wire.create(poly.get(k), poly.get(k + 1)));
            }
            planned.add(Wire.create(tj, mj));
        }

        if (ok) {
            CircuitMutation mut = new CircuitMutation(circ);
            for (Wire w : planned) mut.add(w);
            for (TunnelInfo ti : grp) mut.remove(ti.comp());
            proj.doAction(mut.toAction(Strings.getter("rewriteBitTunnelsAction")));
        } else {
            // Fallback: convertir BLTs del grupo a Tunnel "plain" cuando no se pudo rutear como wires
            CircuitMutation mut = new CircuitMutation(circ);

            for (TunnelInfo ti : grp) {
                try {
                    Component old = ti.comp();
                    AttributeSet asOld = old.getAttributeSet();

                    // WIDTH del BLT
                    BitWidth bw = (asOld != null) ? asOld.getValue(StdAttr.WIDTH) : null;
                    int width = Math.max(1, bw == null ? ti.tokensNorm().size() : bw.getWidth());

                    // LABEL del BLT
                    String label = (asOld != null) ? asOld.getValue(StdAttr.LABEL) : SpecBuilder.makePrettyLabel(ti.tokensNorm());

                    // FACING del BLT
                    Direction facing = Direction.EAST;
                    try {
                        Direction v = (asOld != null) ? asOld.getValue(StdAttr.FACING) : null;
                        if (v != null) facing = v;
                    } catch (Throwable ignore) { }

                    Tunnel tunnelF = Tunnel.FACTORY;

                    // Atributos del Tunnel
                    AttributeSet a = tunnelF.createAttributeSet();
                    try { a.setValue(StdAttr.WIDTH, BitWidth.create(width)); } catch (Throwable ignore) {}
                    try { a.setValue(StdAttr.FACING, facing); } catch (Throwable ignore) {}
                    if (label != null && !label.isBlank()) {
                        try { a.setValue(StdAttr.LABEL, label); } catch (Throwable ignore) {}
                    }

                    // Colocar el Tunnel de forma que su pin coincida EXACTO con la boca del BLT
                    Location mouth = ti.mouth();
                    Component probe = tunnelF.createComponent(Location.create(0, 0), a);
                    EndData end0 = probe.getEnd(0);
                    int offX = end0.getLocation().getX() - probe.getLocation().getX();
                    int offY = end0.getLocation().getY() - probe.getLocation().getY();
                    Location place = Location.create(mouth.getX() - offX, mouth.getY() - offY);

                    // Encolar: quitar BLT y añadir Tunnel
                    mut.remove(old);
                    mut.add(tunnelF.createComponent(place, a));
                } catch (Throwable t) {
                    // falla local: continuamos con el resto
                    t.printStackTrace();
                }
            }

            if (!mut.isEmpty()) {
                proj.doAction(mut.toAction(Strings.getter("rewriteBitTunnelsToPlainTunnelsAction")));
            }
        }
    }

    // === Data model ============================================================
    public record TunnelInfo(Component comp, Location mouth, String labelNorm, List<String> tokensNorm) {}
    private record GroupKey(String labelNorm, List<String> tokensNorm) {}

    /** Recollects all BitLabeledTunnels in the circuit.
     * @param circ Circuit to scan.
     * @return List of TunnelInfo found.
     */
    private static List<TunnelInfo> collectBlt(Circuit circ) {
        List<TunnelInfo> out = new ArrayList<>();
        for (Component c : circ.getNonWires()) {
            if (!(c.getFactory() instanceof BitLabeledTunnel)) continue;

            AttributeSet as = c.getAttributeSet();
            String csv = safe(as, BitLabeledTunnel.BIT_SPECS);
            List<String> toks = parseSpecs(csv);

            List<String> norm = new ArrayList<>(toks.size());
            for (String t : toks) norm.add(normalizeToken(t));

            String label = safe(as, StdAttr.LABEL);
            String labelNorm = (label == null) ? "" : label.trim();

            EndData e = c.getEnd(0);
            if (e == null) continue;
            Location mouth = e.getLocation();

            out.add(new TunnelInfo(c, mouth, labelNorm, norm));
        }
        return out;
    }

    private static Map<GroupKey, List<TunnelInfo>> groupByLabelAndSpecs(List<TunnelInfo> all) {
        Map<GroupKey, List<TunnelInfo>> map = new LinkedHashMap<>();
        for (TunnelInfo ti : all) {
            GroupKey k = new GroupKey(ti.labelNorm(), ti.tokensNorm());
            map.computeIfAbsent(k, __ -> new ArrayList<>()).add(ti);
        }
        return map;
    }

    /**
     * Un grupo es “aislado” si ningún token relevante (no 0/1/x) aparece
     * en túneles que estén fuera de este mismo grupo (con diferentes specs o label).
     */
    private static boolean isGroupIsolated(GroupKey k, Map<GroupKey, List<TunnelInfo>> groups) {
        // tokens relevantes del grupo (sin 0/1/x)
        Set<String> relevant = new HashSet<>();
        for (String t : k.tokensNorm) {
            String r = normalizeToRelevant(t);
            if (!r.isEmpty()) relevant.add(r);
        }
        if (relevant.isEmpty()) return true; // sólo constantes/x → reescribible

        for (Map.Entry<GroupKey, List<TunnelInfo>> e : groups.entrySet()) {
            if (e.getKey().equals(k)) continue;
            for (String t : e.getKey().tokensNorm) {
                String r = normalizeToRelevant(t);
                if (!r.isEmpty() && relevant.contains(r)) {
                    return false; // comparte net/token con fuera del grupo
                }
            }
        }
        return true;
    }

    // === Helpers de specs/normalización =======================================
    private static String safe(AttributeSet as, Attribute<?> attr) {
        try {
            Object v = as.getValue(attr);
            return (v == null) ? "" : String.valueOf(v);
        } catch (Throwable ignore) {
            return "";
        }
    }

    private static List<String> parseSpecs(String csv) {
        List<String> out = new ArrayList<>();
        if (csv == null || csv.isBlank()) return out;
        for (String t : csv.split(",")) out.add(t.trim());
        return out;
    }

    /** Normaliza el token del CSV:
     *  - "0","1","x"/"X" → tal cual en minúscula
     *  - "N123" → "N123" (en mayúscula la 'N')
     *  - cualquier otra cosa → trim, tal cual (puedes endurecer aquí si quieres)
     */
    private static String normalizeToken(String t) {
        if (t == null) return "";
        t = t.trim();
        if (t.isEmpty()) return "";
        if ("0".equals(t) || "1".equals(t)) return t;
        if ("x".equalsIgnoreCase(t)) return "x";
        if (t.length() >= 2 && (t.charAt(0) == 'N' || t.charAt(0) == 'n')) {
            try { int id = Integer.parseInt(t.substring(1).trim()); return "N" + id; } catch (NumberFormatException ignore) { }
        }
        return t;
    }

    /** Relevancia: "" para 0/1/x; para el resto, la misma clave que normalizeToken. */
    private static String normalizeToRelevant(String t) {
        t = normalizeToken(t);
        if (t.isEmpty() || "0".equals(t) || "1".equals(t) || "x".equals(t)) return "";
        return t;
    }
}
