package com.cburch.logisim.verilog.comp.factories.gatelvl;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.VerilogCellFactory;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.impl.GateLvlCellImpl;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.CellAttribs;
import com.cburch.logisim.verilog.comp.specs.CellParams;
import com.cburch.logisim.verilog.comp.specs.GenericCellAttribs;
import com.cburch.logisim.verilog.comp.specs.gatelvl.GateOp;
import com.cburch.logisim.verilog.comp.specs.gatelvl.GateOpParams;

import java.util.List;
import java.util.Map;

public class GateOpFactory extends AbstractVerilogCellFactory implements VerilogCellFactory {
    @Override
    public VerilogCell create(
            String name,
            String type,
            Map<String, String> params,
            Map<String, Object> attribs,
            Map<String, String> ports,
            Map<String, List<Object>> connections
    ) {
        GateOp op = GateOp.fromYosys(type);
        CellParams parameters = new GateOpParams(op, params);
        CellAttribs attributes = new GenericCellAttribs(attribs);
        GateLvlCellImpl cell = new GateLvlCellImpl(name, CellType.fromYosys(type), parameters, attributes);
        buildEndpoints(cell, ports, connections);
        return cell;
    }
}
