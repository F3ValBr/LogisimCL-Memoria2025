package com.cburch.logisim.verilog.comp.factories.wordlvl;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.specs.CommonOpAttribs;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOpParams;

import java.util.List;
import java.util.Map;

/**
 * Factory class for creating unary operation Verilog cells.
 * Supports operations like NOT, NEG, etc.
 */
public class UnaryOpFactory extends AbstractVerilogCellFactory {
    @Override
    public VerilogCell create(
            String name,
            String type,
            Map<String, String> params,
            Map<String, Object> attribs,
            Map<String, String> ports,
            Map<String, List<Object>> connections
    ) {
        UnaryOp op = UnaryOp.fromYosys(type);
        UnaryOpParams parameters = new UnaryOpParams(op, params);
        CommonOpAttribs attributes = new CommonOpAttribs(attribs);
        WordLvlCellImpl cell = new WordLvlCellImpl(name, CellType.fromYosys(type), parameters, attributes);
        buildEndpoints(cell, ports, connections);
        return cell;
    }
}
