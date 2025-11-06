package com.cburch.logisim.verilog.comp.impl;

import com.cburch.logisim.verilog.comp.auxiliary.ModulePort;
import com.cburch.logisim.verilog.comp.auxiliary.NetnameEntry;

import java.util.List;

/**
 * Represents a Verilog module with a name, ports, and cells.
 * Provides methods to access the name, ports, and cells,
 * as well as to add new ports and cells.
 */
public interface VerilogModule {
    String name();
    List<ModulePort> ports();
    List<VerilogCell> cells();
    List<NetnameEntry> netnames();

    void addCell(VerilogCell cell);
    void addModulePort(ModulePort p);
    void addNetname(NetnameEntry entry);
}