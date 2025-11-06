package com.cburch.logisim.verilog.comp.factories.wordlvl;

import com.cburch.logisim.verilog.comp.*;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.specs.CommonOpAttribs;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOpParams;

import java.util.List;
import java.util.Map;

/**
 * Factory class for creating binary operation Verilog cells.
 * Supports operations like AND, OR, XOR, ADD, SUB, etc.
 */
public class BinaryOpFactory extends AbstractVerilogCellFactory {
    @Override
    public VerilogCell create(
            String name,
            String type,
            Map<String, String> params,
            Map<String, Object> attribs,
            Map<String, String> ports,
            Map<String, List<Object>> connections
    ) {
        BinaryOp op = BinaryOp.fromYosys(type);
        BinaryOpParams parameters = new BinaryOpParams(op, params);
        CommonOpAttribs attributes = new CommonOpAttribs(attribs);
        WordLvlCellImpl cell = new WordLvlCellImpl(name, CellType.fromYosys(type), parameters, attributes);
        buildEndpoints(cell, ports, connections);
        return cell;
    }
}

