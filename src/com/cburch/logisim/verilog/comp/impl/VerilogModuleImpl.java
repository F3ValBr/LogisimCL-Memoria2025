package com.cburch.logisim.verilog.comp.impl;

import com.cburch.logisim.verilog.comp.auxiliary.ModulePort;
import com.cburch.logisim.verilog.comp.auxiliary.NetnameEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Implementation of VerilogModule interface.
 * Represents a Verilog module with a name, ports, and cells.
 * Provides methods to add ports and cells.
 * Immutable accessors for name, ports, and cells.
 */
public final class VerilogModuleImpl implements VerilogModule {
    private final String name;
    private final List<ModulePort> ports = new ArrayList<>();
    private final List<VerilogCell> cells = new ArrayList<>();
    private final List<NetnameEntry> netnames = new ArrayList<>();

    public VerilogModuleImpl(String name) {
        this.name = Objects.requireNonNull(name);
    }

    @Override public String name() { return name; }

    @Override public List<ModulePort> ports() { return List.copyOf(ports); }

    @Override public List<VerilogCell> cells() { return List.copyOf(cells); }

    @Override public List<NetnameEntry> netnames() {
        return List.copyOf(netnames);
    }

    @Override public void addCell(VerilogCell cell) {
        cells.add(cell);
    }

    @Override public void addModulePort(ModulePort p) {
        ports.add(p);
    }

    @Override public void addNetname(NetnameEntry entry) {
        netnames.add(entry);
    }

    @Override public String toString() {
        return "VerilogModule{" + name +
                ", ports=" + ports.size() +
                ", cells=" + cells.size() + "}";
    }
}
