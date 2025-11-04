package com.cburch.logisim.verilog.std.adapters.wordlvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.LogicalMemory;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MemoryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.memoryparams.memarrayparams.MemV2Params;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.std.AbstractComponentAdapter;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.*;
import java.util.LinkedHashMap;
import java.util.Map;

public final class MemoryOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();

    // Contexto inyectado por el importador
    private MemoryIndex currentMemIndex;
    private VerilogModuleImpl currentModule;

    // Evita múltiples instancias por MEMID
    private final java.util.Set<String> createdMemIds = new java.util.HashSet<>();

    private record LibFactory(Library lib, ComponentFactory factory) { }

    /** Llamar desde el importador justo después de construir el MemoryIndex del módulo. */
    public void beginModule(MemoryIndex idx, VerilogModuleImpl mod) {
        this.currentMemIndex = idx;
        this.currentModule = mod;
        this.createdMemIds.clear();
    }

    @Override
    public boolean accepts(CellType t) {
        if (t == null) return false;
        if (t.isMemory()) return true;
        String id = t.typeId();
        return MemoryOp.isMemoryTypeId(id);
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        try {
            if (cell == null || cell.type() == null) return fallback.create(proj, circ, g, cell, where);
            if (currentMemIndex == null) return fallback.create(proj, circ, g, cell, where);

            final String typeId = cell.type().typeId();
            if (typeId == null) return fallback.create(proj, circ, g, cell, where);

            // === Dispatcher por familia/tipo ===
            if (typeId.startsWith(MemoryOp.MEM_V2.yosysId())) {
                return handleCreateMemV2(proj, circ, g, cell, where);
            }

            // (futuro) enganchar otras variantes aquí:
            // if (typeId.startsWith(TYPE_MEM_PREFIX))   return handleCreateMem(...);
            // if (typeId.startsWith(TYPE_MEMRD_PREFIX)) return handleCreateMemRd(...);
            // if (typeId.startsWith(TYPE_MEMWR_PREFIX)) return handleCreateMemWr(...);

            return fallback.create(proj, circ, g, cell, where);

        } catch (CircuitException e) {
            throw new IllegalStateException("MemoryOpAdapter: " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        if (cell == null || cell.type() == null) return null;
        String typeId = cell.type().typeId();
        if (typeId == null) return null;

        // === Dispatcher por familia/tipo ===
        if (typeId.startsWith(MemoryOp.MEM_V2.yosysId())) {
            return handlePeekFactoryMemV2(proj, cell);
        }

        // (futuro) enganchar otras variantes aquí:
        // if (typeId.startsWith(TYPE_MEM_PREFIX))   return handlePeekFactoryMem(...);
        // if (typeId.startsWith(TYPE_MEMRD_PREFIX)) return handlePeekFactoryMemRd(...);
        // if (typeId.startsWith(TYPE_MEMWR_PREFIX)) return handlePeekFactoryMemWr(...);

        return null;
    }

    /* ===================== $mem_v2 ===================== */

    /**
     * Crea una instancia a partir de un cell $mem_v2.
     * - Soporta 1R/(0|1)W.
     * - Deduplica por MEMID.
     * - Usa ROM/RAM según haya puerto de escritura.
     */
    private InstanceHandle handleCreateMemV2(Project proj, Circuit circ, Graphics g,
                                             VerilogCell cell, Location where) throws CircuitException {
        // Parametrización $mem_v2
        MemV2Params p = (MemV2Params) cell.params();
        if (p == null) return fallback.create(proj, circ, g, cell, where);

        final String memId = p.memId();
        if (memId == null || memId.isBlank()) return fallback.create(proj, circ, g, cell, where);

        // Índice lógico (compartido entre celdas relacionadas)
        LogicalMemory lm = (currentMemIndex != null) ? currentMemIndex.get(memId) : null;
        if (lm == null) return fallback.create(proj, circ, g, cell, where);

        // ¿ya instanciada esta memoria lógica?
        if (createdMemIds.contains(memId)) {
            return new InstanceHandle(null, null);
        }

        // Chequeo de forma soportada (1R/(0|1)W)
        if (!supportsMemV2Shape(p)) {
            return fallback.create(proj, circ, g, cell, where);
        }

        InstanceHandle ih = createFromMemV2(proj, circ, g, where, p, lm);
        createdMemIds.add(memId);
        return ih;
    }

    /** Devuelve ROM/RAM según peek de $mem_v2 (o null si no soporta). */
    private ComponentFactory handlePeekFactoryMemV2(Project proj, VerilogCell cell) {
        MemV2Params p = (MemV2Params) cell.params();
        if (p == null) return null;

        boolean isRam = p.wrPorts() > 0;
        LibFactory lf = pickMemoryFactory(proj, isRam);
        return lf == null ? null : lf.factory;
    }

    /** Forma soportada: exactamente 1 read port y 0 o 1 write ports. */
    private static boolean supportsMemV2Shape(MemV2Params p) {
        return p != null && p.rdPorts() == 1 && (p.wrPorts() == 0 || p.wrPorts() == 1);
    }

    private InstanceHandle createFromMemV2(Project proj, Circuit circ, Graphics g,
                                           Location where,
                                           MemV2Params p,
                                           LogicalMemory lm) throws CircuitException {
        final boolean isRam = p.wrPorts() > 0;

        // 1) elegir factory
        LibFactory lf = pickMemoryFactory(proj, isRam);
        if (lf == null) return fallback.create(proj, circ, g, /*dummy*/ null, where);

        // 2) atributos
        int width = Math.max(1, p.width());
        int abits = Math.max(1, p.abits());

        AttributeSet attrs = lf.factory.createAttributeSet();
        setOptionByName(attrs, "bus", "separate");
        setParsedByName(attrs, "dataWidth", Integer.toString(width));
        setParsedByName(attrs, "addrWidth", Integer.toString(abits));
        setParsedIfPresent(attrs, "label", cleanCellName(lm.memId()));

        try {
            // clock polarity (si aplica)
            boolean rising = p.wrClkPolarity().get(0);
            attrs.setValue(StdAttr.TRIGGER, rising ? StdAttr.TRIG_RISING : StdAttr.TRIG_FALLING);
        } catch (Throwable ignore) { }

        Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

        // 3) port-map
        Map<String, Integer> nameToIdx = BuiltinPortMaps.forFactory(lf.lib, lf.factory, comp);
        if (nameToIdx == null || nameToIdx.isEmpty()) {
            // Fallback estable:
            // ROM: A, Q
            // RAM: A, D, WE, EN, CLK, Q
            LinkedHashMap<String,Integer> m = new LinkedHashMap<>();
            if (isRam) {
                m.put("A",   0);
                m.put("D",   1);
                m.put("WE",  2);
                m.put("EN",  3);
                m.put("CLK", 4);
                m.put("Q",   5);
            } else {
                m.put("A", 0);
                m.put("Q", 1);
            }
            nameToIdx = m;
        }

        return new InstanceHandle(comp, PortGeom.of(comp, nameToIdx));
    }

    /* ===================== Helpers comunes ===================== */

    private static LibFactory pickMemoryFactory(Project proj, boolean hasWrite) {
        if (proj == null || proj.getLogisimFile() == null) return null;
        String compName = hasWrite ? "RAM" : "ROM";

        Library mem = proj.getLogisimFile().getLibrary("Memory");
        if (mem != null) {
            ComponentFactory f = FactoryLookup.findFactory(mem, compName);
            if (f != null) return new LibFactory(mem, f);
        }
        Library yosys = proj.getLogisimFile().getLibrary("Yosys Components");
        if (yosys != null) {
            ComponentFactory f = FactoryLookup.findFactory(yosys, compName);
            if (f != null) return new LibFactory(yosys, f);
        }
        return null;
    }
}
