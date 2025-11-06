package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Constant;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.BitRef;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.Const0;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.awt.Graphics;
import java.util.*;

import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.setParsedByName;

final class ConstantPlacer {
    private final int grid;

    /** Creates a new constant placer.
     * @param grid Grid size to use for placement.
     */
    ConstantPlacer(int grid) { this.grid = grid; }

    /**
     * Places constants for all-constant signals.
     * @param batch Import batch where to add new components.
     * @param proj Project where to add new components.
     * @param mod Module being imported.
     * @param cellHandles Map from cells to their instance handles.
     * @param topAnchors Map from top module ports to their anchors.
     * @param g Graphics context for measuring components.
     * @param specs Specification builder to use for analyzing specifications.
     */
    void place(ImportBatch batch,
               Project proj,
               VerilogModuleImpl mod,
               Map<VerilogCell, InstanceHandle> cellHandles,
               Map<ModulePort, LayoutServices.PortAnchor> topAnchors,
               Graphics g,
               SpecBuilder specs) {

        // ===== Celdas =====
        for (VerilogCell cell : mod.cells()) {
            InstanceHandle ih = cellHandles.get(cell);
            if (ih == null || ih.ports == null) continue;

            // Agrupar endpoints por nombre de puerto
            Map<String, List<PortEndpoint>> byPort = new LinkedHashMap<>();
            for (PortEndpoint ep : cell.endpoints()) {
                byPort.computeIfAbsent(ep.getPortName(), __ -> new ArrayList<>()).add(ep);
            }

            for (var e : byPort.entrySet()) {
                String pName = e.getKey();
                int w = Math.max(1, cell.portWidth(pName));
                if (w <= 0) continue;

                // Mapear por índice de bit
                PortEndpoint[] byIdx = new PortEndpoint[w];
                for (PortEndpoint ep : e.getValue()) {
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < w) byIdx[i] = ep;
                }

                // ¿Todos presentes y 0/1?
                SpecBuilder.ConstAnalysis ca = SpecBuilder.analyzeConstBits(byIdx);
                if (!(ca.allPresent() && ca.all01())) continue;

                Location mouth = ih.ports.locateByName(pName);
                if (mouth == null) continue;

                // Construir bits exactos (sin overflow de int)
                List<Integer> bits = new ArrayList<>(w);
                for (int i = 0; i < w; i++) {
                    PortEndpoint ep = byIdx[i];
                    // ca ya garantiza 0/1, así que no habrá X ni nets
                    BitRef br = ep.getBitRef();
                    int b = (br instanceof Const0) ? 0 : 1; // sólo 0 o 1
                    bits.add(b);
                }

                Direction facing = LayoutServices.facingByNearestBorder(LayoutServices.figureBounds(ih.component, g), mouth);
                placeConstantChunks(batch, proj, mouth, facing, bits);
            }
        }

        // ===== TOP =====
        for (ModulePort p : mod.ports()) {
            var anc = topAnchors.get(p);
            if (anc == null) continue;

            List<String> bitSpecs = specs.buildBitSpecsForTopPort(mod.name(), p);
            boolean all01 = bitSpecs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (!all01) continue;

            // Bits exactos (0/1) desde bitSpecs
            int w = Math.max(1, p.width());
            List<Integer> bits = new ArrayList<>(w);
            for (int i = 0; i < w; i++) {
                String s = (i < bitSpecs.size()) ? bitSpecs.get(i) : "0";
                bits.add("1".equals(s) ? 1 : 0);
            }

            Direction facing = (anc.facing() == Direction.EAST) ? Direction.WEST : Direction.EAST;
            placeConstantChunks(batch, proj, anc.loc(), facing, bits);
        }
    }

    /* ===================== Colocación por chunks (hasta 32 bits) ===================== */

    /**
     * Puts constant chunks (≤32 bits) at/near the given mouth location.
     * The first chunk (≤32 bits) is placed connected to the mouth;
     * remaining chunks (if any) are placed as decorative constants
     * further back from the mouth, without wires.
     * @param batch Import batch where to add new components.
     * @param proj Project where to add new components.
     * @param mouth Location of the "mouth" where to connect the first constant.
     * @param facing Direction the mouth is facing (to determine connection side).
     * @param bitsLSBFirst List of bits (0/1) in LSB-first order.
     */
    private void placeConstantChunks(ImportBatch batch,
                                     Project proj,
                                     Location mouth,
                                     Direction facing,
                                     List<Integer> bitsLSBFirst) {
        if (bitsLSBFirst == null || bitsLSBFirst.isEmpty()) return;

        final int total = bitsLSBFirst.size();
        final int firstLen = Math.min(32, total);
        final int firstVal = packBitsToInt(bitsLSBFirst, 0, firstLen);

        // 1) Constante conectada con los primeros ≤32 bits
        createConstantNearConnected(batch, proj, mouth, firstLen, firstVal, facing);

        // 2) Restantes (si los hay): constantes sin cable
        int placed = firstLen;
        int chunkIdx = 0;
        while (placed < total) {
            int len = Math.min(32, total - placed);
            int val = packBitsToInt(bitsLSBFirst, placed, len);

            // Colocar el chunk sin cable, desplazado hacia atrás de la boca
            Location where = ImporterUtils.Geom.stepFrom(mouth, facing, (9 + 2 * chunkIdx) * grid);
            createConstantDecorative(batch, proj, where, len, val, facing, ">32 bits");
            placed += len;
            chunkIdx++;
        }
    }

    /**
     * Empaqueta len bits (LSB-first) en un int.
     * bitsLSBFirst.get(i) = bit i (0 o 1).
     */
    private static int packBitsToInt(List<Integer> bitsLSBFirst, int offset, int len) {
        int v = 0;
        for (int i = 0; i < len; i++) {
            int b = (offset + i < bitsLSBFirst.size()) ? (bitsLSBFirst.get(offset + i) != 0 ? 1 : 0) : 0;
            v |= (b << i);
        }
        return v;
    }

    /* ===================== Constructores de constantes ===================== */

    /** Creates a constant component near a given location, connected by a wire.
     * The constant is placed one grid step away from the mouth in the opposite direction of facing.
     * @param batch Import batch where to add new components.
     * @param proj Project where to add new components.
     * @param mouth Location where the wire should connect to.
     * @param width Bit width of the constant.
     * @param value Value of the constant.
     * @param facing Direction the mouth is facing (the constant will be placed opposite).
     */
    private void createConstantNearConnected(ImportBatch batch,
                                             Project proj,
                                             Location mouth,
                                             int width,
                                             int value,
                                             Direction facing) {
        try {
            Location kLoc = ImporterUtils.Geom.stepFrom(mouth, facing, grid);

            ComponentFactory constF = Constant.FACTORY;
            AttributeSet a = constF.createAttributeSet();
            try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) {}
            try { a.setValue(StdAttr.FACING, facing); } catch (Exception ignore) {}

            // Establecer valor (flexible a Integer/Value/parse)
            try { ImporterUtils.Attrs.setConstantValueFlexible(a, width, value); }
            catch (Throwable ignore) { setParsedByName(a, "value", "0x" + Integer.toHexString(value)); }

            // Alinear pin al punto kLoc
            Component probe = constF.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location constLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

            batch.add(Wire.create(kLoc, mouth));
            batch.add(constF.createComponent(constLoc, a));
        } catch (Throwable ignore) { }
    }

    /** Constante sin cable (solo recordatorio visual para overflow). */
    private void createConstantDecorative(ImportBatch batch,
                                          Project proj,
                                          Location where,
                                          int width,
                                          int value,
                                          Direction facing,
                                          String noteSuffix) {
        try {
            ComponentFactory constF = Constant.FACTORY;
            AttributeSet a = constF.createAttributeSet();
            try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) {}
            try { a.setValue(StdAttr.FACING, facing); } catch (Exception ignore) {}

            // Valor
            try { ImporterUtils.Attrs.setConstantValueFlexible(a, width, value); }
            catch (Throwable ignore) { setParsedByName(a, "value", "0x" + Integer.toHexString(value)); }

            // Etiqueta opcional para advertir overflow
            try {
                String base = "";
                try { base = String.valueOf(a.getValue(StdAttr.LABEL)); } catch (Throwable ignore) {}
                String lbl = (base == null || base.isBlank()) ? noteSuffix : (base + " " + noteSuffix);
                a.setValue(StdAttr.LABEL, lbl);
            } catch (Throwable ignore) { }

            // Alinear el pin en 'where' (como si fuera la boca, pero sin cable)
            Component probe = constF.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location constLoc = Location.create(where.getX() - offX, where.getY() - offY);

            batch.add(constF.createComponent(constLoc, a));
        } catch (Throwable ignore) { }
    }
}
