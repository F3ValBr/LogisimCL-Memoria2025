package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.BitLabeledTunnel;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.awt.Graphics;
import java.util.*;

import static com.cburch.logisim.verilog.file.importer.ImporterUtils.NetnameUtils.resolveNetname;

final class TunnelPlacer {
    private final int grid;

    /** Creates a new tunnel placer.
     * @param grid Grid size to use for placement.
     */
    TunnelPlacer(int grid) { this.grid = grid; }

    /**
     * Places tunnels for all non-constant signals.
     * @param batch Import batch where to add new components.
     * @param mod Module being imported.
     * @param cellHandles Map from cells to their instance handles.
     * @param topAnchors Map from top module ports to their anchors.
     * @param g Graphics context for measuring components.
     * @param specs Specification builder to use for analyzing specifications.
     */
    void place(ImportBatch batch,
               VerilogModuleImpl mod,
               Map<VerilogCell, InstanceHandle> cellHandles,
               Map<ModulePort, LayoutServices.PortAnchor> topAnchors,
               Graphics g,
               SpecBuilder specs) {

        record K(int x, int y, String lbl, boolean out) {}
        Set<K> placed = new HashSet<>();

        // ===== TOP ports =====
        for (ModulePort p : mod.ports()) {
            var anc = topAnchors.get(p);
            if (anc == null) continue;

            List<String> bitSpecs = specs.buildBitSpecsForTopPort(mod.name(), p);
            boolean all01 = bitSpecs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (all01) continue; // constantes puras: las maneja ConstantPlacer

            boolean attrOutput = (p.direction() == PortDirection.OUTPUT);
            Direction facing = (anc.facing() == Direction.EAST) ? Direction.WEST : Direction.EAST;
            String pretty = SpecBuilder.makePrettyLabel(bitSpecs);
            String label  = resolveNetname(mod, bitSpecs)
                    .orElse(pretty);

            Location kLoc = ImporterUtils.Geom.stepFrom(anc.loc(), facing, -grid);
            K key = new K(kLoc.getX(), kLoc.getY(), pretty, attrOutput);
            if (!placed.add(key)) continue;

            int w = Math.max(1, p.width());
            if (w <= 32) {
                createBitLabeledTunnel(batch, anc.loc(), w, bitSpecs, label, facing, attrOutput);
            } else {
                placeOverflowTunnels(batch, anc.loc(), w, bitSpecs, facing, attrOutput);
            }
        }

        // ===== Celdas =====
        for (var e : cellHandles.entrySet()) {
            VerilogCell cell = e.getKey();
            InstanceHandle ih = e.getValue();
            if (ih == null || ih.ports == null) continue;

            for (String port : cell.getPortNames()) {
                int w = Math.max(1, cell.portWidth(port));

                // Saltar puertos constantes
                PortEndpoint[] byIdx = new PortEndpoint[w];
                for (PortEndpoint ep : cell.endpoints()) {
                    if (!port.equals(ep.getPortName())) continue;
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < w) byIdx[i] = ep;
                }
                SpecBuilder.ConstAnalysis ca = SpecBuilder.analyzeConstBits(byIdx);
                if (ca.allPresent() && ca.all01()) continue;

                Location pin = ih.ports.locateByName(port);
                if (pin == null) continue;

                List<String> bitSpecs = specs.buildBitSpecsForCellPort(mod.name(), cell, port);
                String pretty = SpecBuilder.makePrettyLabel(bitSpecs);
                String label  = resolveNetname(mod, bitSpecs)
                        .orElse(pretty);

                Direction facing = LayoutServices.facingByNearestBorder(ih.component.getBounds(g), pin);
                boolean attrOutput = (SpecBuilder.dirForPort(cell, port) == SpecBuilder.Dir.IN);

                Location kLoc = ImporterUtils.Geom.stepFrom(pin, facing, grid);
                K key = new K(kLoc.getX(), kLoc.getY(), pretty, attrOutput);
                if (!placed.add(key)) continue;

                if (w <= 32) {
                    createBitLabeledTunnel(batch, pin, w, bitSpecs, label, facing, attrOutput);
                } else {
                    placeOverflowTunnels(batch, pin, w, bitSpecs, facing, attrOutput);
                }
            }
        }
    }

    /** Creates and places a bit-labeled tunnel with a wire connecting to the mouth location.
     * @param batch Import batch where to add new components.
     * @param mouth Location where the tunnel should connect.
     * @param width Width of the tunnel in bits.
     * @param bitSpecs List of bit specifications for the tunnel.
     * @param label Optional label for the tunnel (can be null or blank).
     * @param facing Direction the tunnel should face.
     * @param attrOutput Whether to set the ATTR_OUTPUT attribute (only if facing is not WEST).
     */
    private void createBitLabeledTunnel(ImportBatch batch,
                                        Location mouth,
                                        int width,
                                        List<String> bitSpecs,
                                        String label,
                                        Direction facing,
                                        boolean attrOutput) {
        try {
            Location kLoc = ImporterUtils.Geom.stepFrom(mouth, facing, -grid);

            batch.add(Wire.create(kLoc, mouth));

            BitLabeledTunnel bltFactory = BitLabeledTunnel.FACTORY;

            AttributeSet a = bltFactory.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
            a.setValue(BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
            a.setValue(BitLabeledTunnel.ATTR_OUTPUT, attrOutput && (facing != Direction.WEST));
            a.setValue(StdAttr.FACING, facing);
            if (label != null && !label.isBlank()) a.setValue(StdAttr.LABEL, label);

            // Alinear pin: igual que tu lógica original
            Component probe = bltFactory.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location tunLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

            batch.add(bltFactory.createComponent(tunLoc, a));
        } catch (Exception ignored) { }
    }

    /* ===================== Overflow helpers ===================== */

    /** Divide en BLT(32) conectado + BLT(resto) sin conectar (solo aviso). */
    private void placeOverflowTunnels(ImportBatch batch,
                                      Location mouth,
                                      int width,
                                      List<String> specs,
                                      Direction facing,
                                      boolean attrOutput) {
        final int LOW = 32;
        final int wEff = Math.max(1, width);
        final int lowCount = Math.min(LOW, wEff);
        final int rest = Math.max(0, wEff - lowCount);

        // Normalizar lista de specs al menos a width elementos
        List<String> safe = new ArrayList<>(wEff);
        for (int i = 0; i < wEff; i++) {
            safe.add(i < specs.size() ? nonNullTrim(specs.get(i)) : "x");
        }

        // 1) BLT(32) conectado
        List<String> lowSpecs = safe.subList(0, lowCount);
        String lblLow = SpecBuilder.makePrettyLabel(lowSpecs);
        createBitLabeledTunnel(batch, mouth, lowCount, lowSpecs, lblLow, facing, attrOutput);

        // 2) BLT(resto) sin conectar (solo recordatorio visual)
        if (rest > 0) {
            List<String> restSpecs = new ArrayList<>(rest);
            for (int i = 0; i < rest; i++) restSpecs.add(safe.get(lowCount + i));
            String lblRest = SpecBuilder.makePrettyLabel(restSpecs);
            Location side = ImporterUtils.Geom.stepFrom(mouth, facing, -3 * grid);
            createBltDecorated(batch, side, rest, restSpecs, lblRest, facing, attrOutput, ">32 bits");
        }
    }

    /** Coloca un BLT sin cable (decorativo/aviso). */
    private void createBltDecorated(ImportBatch batch,
                                    Location where,
                                    int width,
                                    List<String> bitSpecs,
                                    String label,
                                    Direction facing,
                                    boolean attrOutput,
                                    String suffixNote) {
        try {
            BitLabeledTunnel bltFactory = BitLabeledTunnel.FACTORY;

            AttributeSet a = bltFactory.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
            a.setValue(BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
            a.setValue(BitLabeledTunnel.ATTR_OUTPUT, attrOutput && (facing != Direction.WEST));
            a.setValue(StdAttr.FACING, facing);
            String lbl = (label == null ? "" : label.trim());
            if (!lbl.isEmpty() && suffixNote != null && !suffixNote.isBlank()) {
                lbl = lbl + " " + suffixNote;
            } else if (lbl.isEmpty() && suffixNote != null && !suffixNote.isBlank()) {
                lbl = suffixNote;
            }
            if (!lbl.isBlank()) a.setValue(StdAttr.LABEL, lbl);

            // Colocar el componente de forma alineada en 'where' (como si fuera la boca),
            // para que quede bien posicionado visualmente aunque no tenga cable.
            Component probe = bltFactory.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location tunLoc = Location.create(where.getX() - offX, where.getY() - offY);

            batch.add(bltFactory.createComponent(tunLoc, a));
        } catch (Exception ignored) { }
    }

    private static String nonNullTrim(String s) {
        return (s == null) ? "x" : s.trim();
    }
}
