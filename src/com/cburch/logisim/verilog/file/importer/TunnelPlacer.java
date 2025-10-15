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

        // TOP ports
        for (ModulePort p : mod.ports()) {
            var anc = topAnchors.get(p);
            if (anc == null) continue;

            List<String> bitSpecs = specs.buildBitSpecsForTopPort(mod.name(), p);
            boolean all01 = bitSpecs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (all01) continue; // No tunnel for all-constant, handled by ConstantPlacer

            boolean attrOutput = (p.direction() == PortDirection.OUTPUT);
            Direction facing = (anc.facing() == Direction.EAST) ? Direction.WEST : Direction.EAST;
            String pretty = SpecBuilder.makePrettyLabel(bitSpecs);

            Location kLoc = ImporterUtils.Geom.stepFrom(anc.loc(), facing, -grid);
            K key = new K(kLoc.getX(), kLoc.getY(), pretty, attrOutput);
            if (placed.add(key)) {
                createBitLabeledTunnel(batch, anc.loc(), Math.max(1, p.width()), bitSpecs, pretty, facing, attrOutput);
            }
        }

        // Cells
        for (var e : cellHandles.entrySet()) {
            VerilogCell cell = e.getKey();
            InstanceHandle ih = e.getValue();
            if (ih == null || ih.ports == null) continue;

            for (String port : cell.getPortNames()) {
                int w = Math.max(1, cell.portWidth(port));

                // Skip constant ports
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

                Direction facing = LayoutServices.facingByNearestBorder(ih.component.getBounds(g), pin);
                boolean attrOutput = (SpecBuilder.dirForPort(cell, port) == SpecBuilder.Dir.IN);

                Location kLoc = ImporterUtils.Geom.stepFrom(pin, facing, grid);
                K key = new K(kLoc.getX(), kLoc.getY(), pretty, attrOutput);
                if (placed.add(key)) {
                    createBitLabeledTunnel(batch, pin, w, bitSpecs, pretty, facing, attrOutput);
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
        Location kLoc = ImporterUtils.Geom.stepFrom(mouth, facing, -grid);

        batch.add(Wire.create(kLoc, mouth));

        BitLabeledTunnel bltFactory = BitLabeledTunnel.FACTORY;

        AttributeSet a = bltFactory.createAttributeSet();
        a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
        a.setValue(BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
        a.setValue(BitLabeledTunnel.ATTR_OUTPUT, attrOutput && (facing != Direction.WEST));
        a.setValue(StdAttr.FACING, facing);
        if (label != null && !label.isBlank()) a.setValue(StdAttr.LABEL, label);

        Component probe = bltFactory.createComponent(Location.create(0, 0), a);
        EndData end0 = probe.getEnd(0);
        int offX = end0.getLocation().getX() - probe.getLocation().getX();
        int offY = end0.getLocation().getY() - probe.getLocation().getY();
        Location tunLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

        batch.add(bltFactory.createComponent(tunLoc, a));
    }
}
