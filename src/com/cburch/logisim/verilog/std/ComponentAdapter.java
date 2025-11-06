package com.cburch.logisim.verilog.std;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;

import java.awt.*;


public interface ComponentAdapter {
    boolean accepts(CellType type);
    InstanceHandle create (Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where);
}
