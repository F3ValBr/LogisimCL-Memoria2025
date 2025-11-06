package com.cburch.logisim.verilog.std.adapters.ips;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.PortGeom;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.PortEndpoint;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.ips.KnownIP;
import com.cburch.logisim.verilog.std.AbstractComponentAdapter;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.InstanceHandle;

import java.awt.*;
import java.util.*;

public final class IPOpAdapter extends AbstractComponentAdapter implements SupportsFactoryLookup {

    @Override
    public boolean accepts(CellType t) {
        if (t == null || !t.isModuleInst()) return false;
        String id = safeTypeId(t);
        if (id == null || id.isBlank()) return false;
        return KnownIP.isKnown(id);
    }

    private static String safeTypeId(CellType t) {
        try { return t.typeId(); } catch (Throwable ignore) { return null; }
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        try {
            String kindId = (cell.typeId() == null) ? "" : cell.typeId();
            return KnownIP.from(kindId)
                    .map(kind -> switch (kind) {
                        case RAM -> createForRam(proj, circ, g, cell, where);
                        case ROM -> createForRom(proj, circ, g, cell, where);
                    })
                    .orElse(null);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir IP " + cell.name() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        LogisimFile lf = proj.getLogisimFile();
        Library mem = lf.getLibrary("Memory");
        if (mem == null) return null;
        String kindId = (cell.typeId() == null) ? "" : cell.typeId();
        return KnownIP.from(kindId)
                .map(kind -> switch (kind) {
                    case RAM -> FactoryLookup.findFactory(mem, "RAM");
                    case ROM -> FactoryLookup.findFactory(mem, "ROM");
                })
                .orElse(null);
    }

    /* ===================== RAM / ROM builders ===================== */

    private InstanceHandle createForRam(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        try {
            LogisimFile lf = proj.getLogisimFile();
            Library mem = lf.getLibrary("Memory");
            if (mem == null) return null;

            ComponentFactory f = FactoryLookup.findFactory(mem, "RAM");
            if (f == null) return null;

            // $3 -> addr, $4 -> dataIn (DIN), $5 -> dataOut (DATA/Q), $1 -> clk, $2 -> we
            int addrW = Math.max(1, widthFromEndpoints(cell, "$3"));
            int dinW  = Math.max(1, widthFromEndpoints(cell, "$4"));
            int doutW = Math.max(1, widthFromEndpoints(cell, "$5"));
            int dataW = Math.max(dinW, doutW);

            AttributeSet attrs = f.createAttributeSet();

            // Forzar BUS_SEPARATE para puertos DIN/WE separados
            setOptionByName(attrs, "bus", "separate");        // Ram.ATTR_BUS
            setBitWidthByName(attrs, "dataWidth", dataW); // Mem.DATA_ATTR
            setBitWidthByName(attrs, "addrWidth", addrW); // Mem.ADDR_ATTR
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) {}

            Component comp = addComponent(proj, circ, g, f, where, attrs);

            // Port map ya viene del MemoryPortMapRegister (resolver dinámico de RAM)
            Map<String,Integer> nameToIdx = BuiltinPortMaps.forFactory(mem, f, comp);

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);

        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir RAM IP " + cell.name() + ": " + e.getMessage(), e);
        }
    }

    private InstanceHandle createForRom(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        try {
            LogisimFile lf = proj.getLogisimFile();
            Library mem = lf.getLibrary("Memory");
            if (mem == null) return null;

            ComponentFactory f = FactoryLookup.findFactory(mem, "ROM");
            if (f == null) return null;

            // $1 -> addr, $2 -> dataOut (DATA/Q)
            int addrW = Math.max(1, widthFromEndpoints(cell, "$1"));
            int dataW = Math.max(1, widthFromEndpoints(cell, "$2"));

            AttributeSet attrs = f.createAttributeSet();

            setBitWidthByName(attrs, "dataWidth", dataW);  // Mem.DATA_ATTR
            setBitWidthByName(attrs, "addrWidth", addrW);  // Mem.ADDR_ATTR
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) {}

            Component comp = addComponent(proj, circ, g, f, where, attrs);

            // Port map ya viene del MemoryPortMapRegister (ROM estático)
            Map<String,Integer> nameToIdx = BuiltinPortMaps.forFactory(mem, f, comp);

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);

        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir ROM IP " + cell.name() + ": " + e.getMessage(), e);
        }
    }

    /* ===================== Helpers ===================== */

    /** Calcula ancho por endpoints de un puerto posicional (max(bitIndex)+1), con fallbacks. */
    private static int widthFromEndpoints(VerilogCell cell, String portName) {
        int maxIdx = -1;
        for (PortEndpoint ep : cell.endpoints()) {
            if (portName.equals(ep.getPortName())) {
                maxIdx = Math.max(maxIdx, ep.getBitIndex());
            }
        }
        if (maxIdx >= 0) return maxIdx + 1;

        int cnt = 0;
        for (PortEndpoint ep : cell.endpoints()) {
            if (portName.equals(ep.getPortName())) cnt++;
        }
        if (cnt > 0) return cnt;

        try {
            int w = cell.portWidth(portName);
            if (w > 0) return w;
        } catch (Throwable ignore) {}
        return 1;
    }
}
