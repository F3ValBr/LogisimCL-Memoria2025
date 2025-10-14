package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Constant;
import com.cburch.logisim.verilog.comp.auxiliary.*;
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

        // Celdas
        for (VerilogCell cell : mod.cells()) {
            InstanceHandle ih = cellHandles.get(cell);
            if (ih == null || ih.ports == null) continue;

            Map<String, List<PortEndpoint>> byPort = new LinkedHashMap<>();
            for (PortEndpoint ep : cell.endpoints()) {
                byPort.computeIfAbsent(ep.getPortName(), __ -> new ArrayList<>()).add(ep);
            }

            for (var e : byPort.entrySet()) {
                String pName = e.getKey();
                int w = Math.max(1, cell.portWidth(pName));
                if (w <= 0) continue;

                PortEndpoint[] byIdx = new PortEndpoint[w];
                for (PortEndpoint ep : e.getValue()) {
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < w) byIdx[i] = ep;
                }

                SpecBuilder.ConstAnalysis ca = SpecBuilder.analyzeConstBits(byIdx);
                if (!(ca.allPresent() && ca.all01())) continue;

                Location mouth = ih.ports.locateByName(pName);
                if (mouth == null) continue;

                Direction facing = LayoutServices.facingByNearestBorder(ih.component.getBounds(g), mouth);
                createConstantNear(batch, proj, mouth, w, ca.acc(), facing);
            }
        }

        // TOP
        for (ModulePort p : mod.ports()) {
            var anc = topAnchors.get(p);
            if (anc == null) continue;

            var bitSpecs = specs.buildBitSpecsForTopPort(mod.name(), p);
            boolean all01 = bitSpecs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (!all01) continue;

            int acc = 0;
            for (int i = 0; i < bitSpecs.size(); i++) if ("1".equals(bitSpecs.get(i))) acc |= (1 << i);

            Direction facing = (anc.facing() == Direction.EAST) ? Direction.WEST : Direction.EAST;
            createConstantNear(batch, proj, anc.loc(), Math.max(1, p.width()), acc, facing);
        }
    }

    /** Creates a constant component near a given location, connected by a wire.
     * The constant is placed one grid step away from the mouth in the opposite direction of facing.
     * @param batch Import batch where to add new components.
     * @param proj Project where to add new components.
     * @param mouth Location where the wire should connect to.
     * @param width Bit width of the constant.
     * @param value Value of the constant.
     * @param facing Direction the mouth is facing (the constant will be placed opposite).
     */
    private void createConstantNear(ImportBatch batch,
                                    Project proj,
                                    Location mouth,
                                    int width,
                                    int value,
                                    Direction facing) {
        Location kLoc = ImporterUtils.Geom.stepFrom(mouth, facing, -grid);

        AttributeSet a = Constant.FACTORY.createAttributeSet();
        try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) {}
        try { a.setValue(StdAttr.FACING, facing); } catch (Exception ignore) {}

        // valor
        boolean ok = false;
        try { ImporterUtils.Attrs.setConstantValueFlexible(a, width, value); ok = true; } catch (Throwable ignore) {}
        if (!ok) setParsedByName(a, "value", "0x" + Integer.toHexString(value));

        Component probe = Constant.FACTORY.createComponent(Location.create(0, 0), a);
        EndData end0 = probe.getEnd(0);
        int offX = end0.getLocation().getX() - probe.getLocation().getX();
        int offY = end0.getLocation().getY() - probe.getLocation().getY();
        Location constLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

        batch.add(Wire.create(kLoc, mouth));
        batch.add(Constant.FACTORY.createComponent(constLoc, a));
    }
}
