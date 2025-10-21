package com.cburch.logisim.verilog.comp.factories.wordlvl;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.VerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.specs.GenericCellAttribs;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MemoryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MemoryOpParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memarrayparams.MemParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memarrayparams.MemV2Params;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.meminitparams.MemInitParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.meminitparams.MemInitV2Params;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memreadparams.MemRDParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memreadparams.MemRDV2Params;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memwriteparams.MemWRParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memwriteparams.MemWRV2Params;

import java.util.List;
import java.util.Map;

/**
 * Factory for creating memory operation Verilog cells.
 * Supports various memory operations like $mem, $mem_v2, $meminit, $memrd, $memwr, etc.
 */
public class MemoryOpFactory extends AbstractVerilogCellFactory implements VerilogCellFactory {

    @Override
    public VerilogCell create(String name,
                              String type,
                              Map<String, String> parameters,
                              Map<String, Object> attributes,
                              Map<String, String> portDirections,
                              Map<String, List<Object>> connections) {

        final MemoryOp op = MemoryOp.fromYosys(type);
        final CellType ct = CellType.fromYosys(type);
        final GenericCellAttribs attribs = new GenericCellAttribs(attributes);

        // 1) Specific params by type
        final MemoryOpParams params = newParams(op, parameters);

        // 2) Cell creation
        final VerilogCell cell = newCell(name, ct, params, attribs);

        // 3) Endpoints
        buildEndpoints(cell, portDirections, connections);

        // 4) Validations
        validatePorts(cell, op, params);

        return cell;
    }

    /* ============================
       Build helpers
       ============================ */

    private MemoryOpParams newParams(MemoryOp op, Map<String, String> parameters) {
        return switch (op) {
            case MEM        -> new MemParams(parameters);      // $mem (v1)
            case MEM_V2     -> new MemV2Params(parameters);    // $mem_v2
            case MEMINIT    -> new MemInitParams(parameters);
            case MEMINIT_V2 -> new MemInitV2Params(parameters);
            case MEMRD      -> new MemRDParams(parameters);
            case MEMRD_V2   -> new MemRDV2Params(parameters);
            case MEMWR      -> new MemWRParams(parameters);
            case MEMWR_V2   -> new MemWRV2Params(parameters);
        };
    }

    /** Creates a new WordLvlCellImpl with the given parameters.
     *
     * @param name Name of the cell
     * @param ct CellType of the cell
     * @param params Specific parameters for the memory operation
     * @param attribs Generic attributes for the cell
     * @return A new VerilogCell instance with the specified configuration
     */
    private VerilogCell newCell(String name, CellType ct, MemoryOpParams params, GenericCellAttribs attribs) {
        return new WordLvlCellImpl(name, ct, params, attribs);
    }

    /* ============================
       Validations by type
       ============================ */

    private void validatePorts(VerilogCell cell, MemoryOp op, MemoryOpParams p) {
        switch (op) {
            case MEM, MEM_V2 -> validateMemArray(cell, p);
            case MEMRD       -> validateMemRd(cell, p, false);
            case MEMRD_V2    -> validateMemRd(cell, p, true);
            case MEMWR, MEMWR_V2 -> validateMemWr(cell, p);
            case MEMINIT, MEMINIT_V2 -> { /* Only params, no wiring */ }
            default -> throw new IllegalArgumentException("MemoryOpFactory: unsupported type: " + op);
        }
    }

    private void validateMemArray(VerilogCell cell, MemoryOpParams p) {
        requirePortWidthOptional(cell, "RD_ADDR", p.abits() * p.rdPorts());
        requirePortWidthOptional(cell, "RD_DATA", p.width()  * p.rdPorts());
        requirePortWidthOptional(cell, "WR_ADDR", p.abits() * p.wrPorts());
        requirePortWidthOptional(cell, "WR_DATA", p.width()  * p.wrPorts());
        // EN por-bit (WIDTH * WR_PORTS).
        if (hasPort(cell, "WR_EN")) {
            requirePortWidth(cell, "WR_EN", p.width() * p.wrPorts());
        }
        // TODO: check buses organization (contiguous, same order)
    }

    private void validateMemRd(VerilogCell cell, MemoryOpParams p, boolean v2) {
        requirePortWidth(cell, "ADDR", p.abits());
        requirePortWidth(cell, "DATA", p.width());
        if (hasPort(cell, "EN"))  requirePortWidth(cell, "EN", 1);
        if (p.clkEnable() && hasPort(cell, "CLK")) requirePortWidth(cell, "CLK", 1);
        if (v2) { // only v2 exposes ARST/SRST
            if (hasPort(cell, "ARST")) requirePortWidth(cell, "ARST", 1);
            if (hasPort(cell, "SRST")) requirePortWidth(cell, "SRST", 1);
        }
    }

    private void validateMemWr(VerilogCell cell, MemoryOpParams p) {
        requirePortWidth(cell, "ADDR", p.abits());
        requirePortWidth(cell, "DATA", p.width());
        // EN each bit (WIDTH).
        if (hasPort(cell, "EN")) requirePortWidth(cell, "EN", p.width());
        if (p.clkEnable() && hasPort(cell, "CLK")) requirePortWidth(cell, "CLK", 1);
    }

    /* ============================
       Port helpers
       ============================ */

    private static void requirePortWidth(VerilogCell cell, String port, int expected) {
        int got = cell.portWidth(port);
        if (got != expected) {
            throw new IllegalStateException(cell.name() + ": port " + port +
                    " width mismatch. expected=" + expected + " got=" + got);
        }
    }

    private static void requirePortWidthOptional(VerilogCell cell, String port, int expected) {
        if (hasPort(cell, port)) requirePortWidth(cell, port, expected);
    }

    private static boolean hasPort(VerilogCell cell, String port) {
        return cell.getPortNames().contains(port);
    }
}

