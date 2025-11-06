package com.cburch.logisim.verilog.std;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.*;
import java.util.ArrayList;
import java.util.List;

public final class ComponentAdapterRegistry {
    private final List<ComponentAdapter> adapters = new ArrayList<>();

    public ComponentAdapterRegistry register(ComponentAdapter a){ adapters.add(a); return this; }

    /** Funcion para crear un componente en un circuito dado. */
    public InstanceHandle create(Project proj, Circuit circuit, Graphics g, VerilogCell cell, Location where) {
        for (ComponentAdapter a : adapters) {
            if (a.accepts(cell.type())) {
                return a.create(proj, circuit, g, cell, where);
            }
        }
        return new ModuleBlackBoxAdapter().create(proj, circuit, g, cell, where);
    }

    public List<ComponentAdapter> getAdapters(){ return adapters; }
}
