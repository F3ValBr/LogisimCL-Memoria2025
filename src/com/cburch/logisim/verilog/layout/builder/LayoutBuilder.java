package com.cburch.logisim.verilog.layout.builder;

import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.comp.auxiliary.ModulePort;
import com.cburch.logisim.verilog.layout.auxiliary.NodeSizer;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.layout.ModuleNetIndex;
import org.eclipse.elk.alg.layered.options.LayeredOptions;
import org.eclipse.elk.core.options.Direction;
import org.eclipse.elk.core.options.EdgeLabelPlacement;
import org.eclipse.elk.core.options.EdgeRouting;
import org.eclipse.elk.graph.*;
import org.eclipse.elk.graph.util.ElkGraphUtil;
import org.eclipse.elk.core.options.CoreOptions;

import java.awt.*;
import java.util.*;
import java.util.List;

public final class LayoutBuilder {

    public static class Result {
        public ElkNode root;
        public final Map<VerilogCell, ElkNode> cellNode = new HashMap<>();
        public final Map<ModulePort, ElkNode>  portNode = new HashMap<>();
        public Result(ElkNode root){ this.root = root; }
    }

    // --- Agrupadores/keys para buses -----------------------------------------

    /** Identifica un “extremo lógico” por (nodo, nombre-de-puerto) para no mezclar buses distintos por el mismo par de nodos. */
    private static final class EpKey {
        final ElkNode node;
        final String portName;
        EpKey(ElkNode node, String portName) {
            this.node = node;
            this.portName = portName;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof EpKey k)) return false;
            return node == k.node && Objects.equals(portName, k.portName);
        }
        @Override public int hashCode() {
            return 31 * System.identityHashCode(node) + Objects.hashCode(portName);
        }
    }

    /** Par dirigido (src,dst) + baseLabel (nombre lógico del bus para la etiqueta). */
    private static final class PairKey {
        final EpKey src, dst;
        final String baseLabel;
        PairKey(EpKey src, EpKey dst, String baseLabel) {
            this.src = src; this.dst = dst; this.baseLabel = baseLabel;
        }
        @Override public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PairKey k)) return false;
            return Objects.equals(src, k.src) &&
                    Objects.equals(dst, k.dst) &&
                    Objects.equals(baseLabel, k.baseLabel);
        }
        @Override public int hashCode() {
            return (31 * src.hashCode() + dst.hashCode()) * 31 + Objects.hashCode(baseLabel);
        }
    }

    private static final class RefInfo {
        final ElkNode node;
        final String portName;
        final int bitIndex;
        RefInfo(ElkNode node, String portName, int bitIndex){
            this.node=node; this.portName=portName; this.bitIndex=bitIndex;
        }
    }

    // --- Utilidades -----------------------------------------------------------

    /** Compacta índices de bit a "0,2-5,7". */
    private static String compactRanges(SortedSet<Integer> idxs) {
        if (idxs == null || idxs.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        Integer start = null, prev = null;
        for (Integer x : idxs) {
            if (start == null) { start = prev = x; continue; }
            if (x == prev + 1) { prev = x; continue; }
            if (start.equals(prev)) sb.append(start);
            else sb.append(start).append("-").append(prev);
            sb.append(",");
            start = prev = x;
        }
        if (start.equals(prev)) sb.append(start);
        else sb.append(start).append("-").append(prev);
        return sb.toString();
    }

    /** Elige una etiqueta base para el bus a partir de nombres de puertos. */
    private static String chooseBaseLabel(String a, String b, int netId) {
        if (a != null && b != null) return a.equals(b) ? a : (a + "→" + b);
        if (a != null) return a;
        if (b != null) return b;
        return "n" + netId;
    }

    // ================= Overload para compatibilidad =================
    public static Result build(Project proj,
                               VerilogModuleImpl mod,
                               ModuleNetIndex netIdx,
                               NodeSizer sizer) {
        return build(proj, mod, netIdx, sizer, Collections.emptyMap());
    }

    // ================= Versión con alias de celdas =================
    public static Result build(Project proj,
                               VerilogModuleImpl mod,
                               ModuleNetIndex netIdx,
                               NodeSizer sizer,
                               Map<VerilogCell, VerilogCell> cellAlias) {
        // --- Grafo raíz y opciones ELK ---
        ElkNode root = ElkGraphUtil.createGraph();
        root.setProperty(CoreOptions.ALGORITHM, "org.eclipse.elk.layered");
        root.setProperty(LayeredOptions.SPACING_NODE_NODE, 70.0);
        root.setProperty(CoreOptions.SPACING_COMPONENT_COMPONENT, 80.0);
        root.setProperty(LayeredOptions.EDGE_ROUTING, EdgeRouting.POLYLINE);
        root.setProperty(CoreOptions.DIRECTION, Direction.RIGHT);
        root.setProperty(LayeredOptions.EDGE_LABELS_PLACEMENT, EdgeLabelPlacement.CENTER);

        Result r = new Result(root);

        // --- 1) Celdas internas como nodos ---
        for (VerilogCell cell : mod.cells()) {
            // si es alias, NO creamos nodo; su representante tendrá el nodo
            if (cellAlias.containsKey(cell)) continue;

            ElkNode n = ElkGraphUtil.createNode(root);

            Dimension d = (sizer != null)
                    ? sizer.sizeForCell(proj, cell)
                    : new Dimension(60, 60);

            n.setWidth(Math.max(30, d.width));
            n.setHeight(Math.max(20, d.height));

            ElkLabel lbl = ElkGraphUtil.createLabel(n);
            lbl.setText(cell.name());

            r.cellNode.put(cell, n);
        }

        // --- 2) Puertos top como nodos ---
        for (ModulePort p : mod.ports()) {
            ElkNode n = ElkGraphUtil.createNode(root);

            Dimension d = (sizer != null)
                    ? sizer.sizeForTopPort(p)
                    : new Dimension(20, 20);

            n.setWidth(Math.max(20, d.width));
            n.setHeight(Math.max(20, d.height));

            ElkLabel lbl = ElkGraphUtil.createLabel(n);
            lbl.setText(p.name());

            r.portNode.put(p, n);
        }

        // --- 3) Aristas agrupadas por bus (src,dst,baseLabel) ---
        Map<PairKey, SortedSet<Integer>> busGroups = new HashMap<>();

        for (int netId : netIdx.netIds()) {
            List<Integer> refs = netIdx.endpointsOf(netId);
            if (refs == null || refs.size() < 2) continue;

            // Resolvemos cada endpoint a (nodo ELK, nombre de puerto, bit)
            List<RefInfo> infos = new ArrayList<>(refs.size());
            for (int ref : refs) {
                int bit = ModuleNetIndex.bitIdx(ref);

                if (ModuleNetIndex.isTop(ref)) {
                    int pIdx = netIdx.resolveTopPortIdx(ref);
                    ModulePort p = mod.ports().get(pIdx);
                    ElkNode node = r.portNode.get(p);
                    String pname = p.name();
                    infos.add(new RefInfo(node, pname, bit));
                } else {
                    int cIdx = ModuleNetIndex.ownerIdx(ref);
                    VerilogCell owner = mod.cells().get(cIdx);
                    // Remapear al representante si es alias
                    VerilogCell repr = cellAlias.getOrDefault(owner, owner);

                    ElkNode node = r.cellNode.get(repr);
                    // Si por alguna razón el repr aún no está en el mapa, créalo on-demand.
                    if (node == null) {
                        node = ElkGraphUtil.createNode(root);
                        Dimension d = (sizer != null) ? sizer.sizeForCell(proj, repr) : new Dimension(60, 60);
                        node.setWidth(Math.max(30, d.width));
                        node.setHeight(Math.max(20, d.height));
                        ElkLabel lbl = ElkGraphUtil.createLabel(node);
                        lbl.setText(repr.name());
                        r.cellNode.put(repr, node);
                    }

                    String pname = netIdx.resolveCellPortName(ref).orElse(null);
                    infos.add(new RefInfo(node, pname, bit));
                }
            }

            // Estrella estable desde el primero
            RefInfo src = infos.get(0);
            for (int i = 1; i < infos.size(); i++) {
                RefInfo dst = infos.get(i);

                String base = chooseBaseLabel(src.portName, dst.portName, netId);
                PairKey key = new PairKey(new EpKey(src.node, src.portName),
                        new EpKey(dst.node, dst.portName),
                        base);

                SortedSet<Integer> set = busGroups.computeIfAbsent(key, k -> new TreeSet<>());
                set.add(src.bitIndex);
                set.add(dst.bitIndex);
            }
        }

        // Crear UNA arista por grupo y etiquetar con rangos de bits
        for (Map.Entry<PairKey, SortedSet<Integer>> e : busGroups.entrySet()) {
            PairKey k = e.getKey();
            ElkEdge edge = ElkGraphUtil.createSimpleEdge(k.src.node, k.dst.node);

            String idxs = compactRanges(e.getValue());
            String label = (k.baseLabel == null || k.baseLabel.isBlank())
                    ? ("bus [" + idxs + "]")
                    : (k.baseLabel + " [" + idxs + "]");

            ElkLabel el = ElkGraphUtil.createLabel(edge);
            el.setText(label);
        }

        return r;
    }
}
