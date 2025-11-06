package com.cburch.logisim.verilog.comp.factories.wordlvl;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.VerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.specs.CommonOpAttribs;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MuxOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MuxOpParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.muxparams.*;

import java.util.List;
import java.util.Map;

/**
 * Factory for creating multiplexer operation Verilog cells.
 * Supports various mux operations like MUX, PMUX, TRIBUF, BMUX, BWMUX, DEMUX.
 */
public class MuxOpFactory extends AbstractVerilogCellFactory implements VerilogCellFactory {

    @Override
    public VerilogCell create(
            String name,
            String type,
            Map<String, String> params,
            Map<String, Object> attribs,
            Map<String, String> ports,
            Map<String, List<Object>> connections
    ) {
        // 1) Classification by enum
        final MuxOp op = MuxOp.fromYosys(type);

        // 2) Specific parameters by op
        final MuxOpParams parameters = getMuxOpParams(op, params);

        // 3) Attribs + cell creation
        final CommonOpAttribs attributes = new CommonOpAttribs(attribs); // or GenericCellAttribs
        final WordLvlCellImpl cell = new WordLvlCellImpl(name, CellType.fromYosys(type), parameters, attributes);

        // 4) Endpoints
        buildEndpoints(cell, ports, connections);

        // 5) Validations of ports by op
        final int w = parameters.width();
        switch (op) {
            case MUX -> {
                // A,B,Y: w bits ; S: 1 bit
                requirePortWidth(cell, "A", w);
                requirePortWidth(cell, "B", w);
                requirePortWidth(cell, "Y", w);
                requirePortWidth(cell, "S", 1);
            }
            case PMUX -> {
                PMuxParams p = (PMuxParams) parameters;
                requirePortWidth(cell, "A", w);
                requirePortWidth(cell, "Y", w);
                requirePortWidth(cell, "S", p.sWidth());
                requirePortWidth(cell, "B", p.bTotalWidth());
            }
            case TRIBUF -> {
                requirePortWidth(cell, "A", w);
                requirePortWidth(cell, "Y", w);
                requirePortWidth(cell, "EN", 1);
            }
            case BMUX -> {
                // Default: A, Y: w;
                requirePortWidth(cell, "A", w);
                requirePortWidth(cell, "Y", w);
                // TODO: validar S según BMuxParams
            }
            case BWMUX -> {
                // TODO: validar puertos según BWMuxParams
            }
            case DEMUX -> {
                // DEMUX: A (w), Y (w * nSel), S (nSel)
                DemuxParams p = (DemuxParams) parameters;
                requirePortWidth(cell, "A", w);
                requirePortWidth(cell, "S", p.sWidth());
            }
        }

        return cell;
    }

    private static MuxOpParams getMuxOpParams(MuxOp op, Map<String, String> params) {
        return switch (op) {
            case MUX -> new MuxParams(params);
            case PMUX -> new PMuxParams(params);
            case TRIBUF -> new TribufParams(params);
            case BMUX -> new BMuxParams(params);
            case BWMUX -> new BWMuxParams(params);
            case DEMUX -> new DemuxParams(params);
        };
    }

    private void requirePortWidth(VerilogCell cell, String port, int expected) {
        int got = cell.portWidth(port);
        if (got != expected) {
            throw new IllegalStateException(cell.name() + ": port " + port +
                    " width mismatch. expected=" + expected + " got=" + got);
        }
    }
}
