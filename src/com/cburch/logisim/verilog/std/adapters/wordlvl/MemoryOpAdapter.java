package com.cburch.logisim.verilog.std.adapters.wordlvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.LogicalMemory;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.comp.specs.CellParams;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.MemoryOp;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.std.AbstractComponentAdapter;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.*;

public final class MemoryOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();

    // Contexto del módulo actual (inyectado por el importador)
    private MemoryIndex currentMemIndex;
    private VerilogModuleImpl currentModule;

    // Evita crear múltiples instancias físicas por el mismo MEMID
    private final java.util.Set<String> createdMemIds = new java.util.HashSet<>();

    /**
     * Llamar desde el importador justo después de construir el MemoryIndex del módulo.
     */
    public void beginModule(MemoryIndex idx, VerilogModuleImpl mod) {
        this.currentMemIndex = idx;
        this.currentModule = mod;
        this.createdMemIds.clear();
    }

    @Override
    public boolean accepts(CellType t) {
        if (t == null) return false;
        // acepta todo lo marcado como memoria y/o con los typeIds típicos
        if (t.isMemory()) return true;
        String id = t.typeId();
        return MemoryOp.isMemoryTypeId(id);
    }

    @Override
    public InstanceHandle create(Canvas canvas, Graphics g, VerilogCell cell, Location where) {
        try {
            // Sin índice → no podemos unificar → fallback
            if (currentMemIndex == null) {
                return fallback.create(canvas, g, cell, where);
            }

            // Lee MEMID de la celda
            String memId = readMemId(cell.params());
            if (memId == null || memId.isEmpty()) {
                return fallback.create(canvas, g, cell, where);
            }

            LogicalMemory lm = currentMemIndex.get(memId);
            if (lm == null) {
                return fallback.create(canvas, g, cell, where);
            }

            // Si ya instanciamos la memoria lógica (por MEMID), no dupliques.
            if (createdMemIds.contains(lm.memId())) {
                // Devuelve un “handle vacío” de cortesía (no se usará para wiring)
                return new InstanceHandle(null, null);
            }

            InstanceHandle ih = createUnifiedRamOrRom(canvas, g, cell, where, lm);
            createdMemIds.add(lm.memId());
            return ih;

        } catch (CircuitException e) {
            throw new IllegalStateException("MemoryOpAdapter: " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        // Si logramos determinar RAM vs ROM aquí, devolvemos esa factory
        String memId = readMemId(cell.params());
        if (memId == null || currentMemIndex == null) return null;

        LogicalMemory lm = currentMemIndex.get(memId);
        if (lm == null) return null;

        boolean hasWrite = !lm.writePortIdxs().isEmpty();
        LibFactory lf = pickMemoryFactory(proj, hasWrite);
        return lf == null ? null : lf.factory;
    }

    /* =======================================================================
       Implementación
       ======================================================================= */

    // Paquete (Library, Factory) para usar BuiltinPortMaps.forFactory(...)
    private static final class LibFactory {
        final Library lib;
        final ComponentFactory factory;

        LibFactory(Library lib, ComponentFactory factory) {
            this.lib = lib;
            this.factory = factory;
        }
    }

    private InstanceHandle createUnifiedRamOrRom(Canvas canvas, Graphics g,
                                                 VerilogCell anyCellOfThisMem,
                                                 Location where,
                                                 LogicalMemory lm)
            throws CircuitException {
        Project proj = canvas.getProject();
        Circuit circ = canvas.getCircuit();

        // 1) Deducir parámetros (width / depth / abits)
        int width = 8;
        int depth = 256;
        int abits = 8;

        // Prioriza meta (si vino del JSON/YosysMemoryDTO)
        if (lm.meta() != null) {
            if (lm.meta().width() > 0) width = lm.meta().width();
            if (lm.meta().size() > 0) depth = lm.meta().size();
        }

        // Si la celda tiene ABITS/WIDTH definidos, (re)ajusta
        if (anyCellOfThisMem.params() instanceof GenericCellParams gp) {
            int w = parseIntRelaxed(gp.asMap().get("WIDTH"), -1);
            int a = parseIntRelaxed(gp.asMap().get("ABITS"), -1);
            if (w > 0) width = w;
            if (a > 0) abits = a;
        }
        if (abits <= 0 && depth > 0) abits = Math.max(1, ceilLog2(depth));
        if (depth <= 0 && abits > 0) depth = 1 << abits;

        boolean hasWrite = !lm.writePortIdxs().isEmpty();

        // 2) Elegir librería/Factory
        LibFactory lf = pickMemoryFactory(proj, hasWrite);
        if (lf == null) {
            return fallback.create(canvas, g, anyCellOfThisMem, where);
        }

        // 3) Atributos para tu RAM/ROM
        AttributeSet attrs = lf.factory.createAttributeSet();

        // Si tu RAM/ROM soporta StdAttr.WIDTH como “ancho de datos”, setéalo también
        try {
            attrs.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
        } catch (Exception ignore) {
        }

        // Y nombres “tolerantes” por token (ajusta a los que use tu implementación real)
        setParsedIfPresent(attrs, "label", cleanCellName(lm.memId()));
        setParsedByName(attrs, "dataWidth", Integer.toString(width));  // si tu comp lo reconoce
        setParsedByName(attrs, "addrWidth", Integer.toString(abits));  // idem
        setParsedByName(attrs, "depth", Integer.toString(depth));   // idem

        Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

        // 4) Mapear puertos con BuiltinPortMaps (si registraste RAM/ROM allí)
        java.util.Map<String, Integer> nameToIdx =
                BuiltinPortMaps.forFactory(lf.lib, lf.factory, comp);

        // Fallback si aún no registraste el port-map:
        if (nameToIdx == null || nameToIdx.isEmpty()) {
            // Suponemos nombres más típicos (ajusta a tu RAM/ROM real):
            // Ej.: RAM: A(addr), D(data in), WE, EN, CLK, Q(data out)
            //      ROM: A(addr), Q(data out)
            java.util.LinkedHashMap<String, Integer> fallbackMap = new java.util.LinkedHashMap<>();
            // En muchas RAM/ROM de Logisim: Q suele ser 0, D 1, A 2, etc. (ajústalo)
            // Para no inventar: deja un mínimo Y/A, como ya tenías:
            fallbackMap.put("A", 0); // addr
            fallbackMap.put("Y", 1); // data out
            nameToIdx = fallbackMap;
        }

        PortGeom pg = PortGeom.of(comp, nameToIdx);
        return new InstanceHandle(comp, pg);
    }

    /**
     * Devuelve (Library, Factory) para RAM o ROM según haya puertos de escritura.
     */
    private static LibFactory pickMemoryFactory(Project proj, boolean hasWrite) {
        if (proj == null || proj.getLogisimFile() == null) return null;

        String compName = hasWrite ? "RAM" : "ROM";

        Library mem = proj.getLogisimFile().getLibrary("Memory");
        if (mem != null) {
            ComponentFactory f = FactoryLookup.findFactory(mem, compName);
            if (f != null) return new LibFactory(mem, f);
        }
        // Segundo intento: tu librería custom (si ahí viven RAM/ROM)
        Library yosys = proj.getLogisimFile().getLibrary("Yosys Components");
        if (yosys != null) {
            ComponentFactory f = FactoryLookup.findFactory(yosys, compName);
            if (f != null) return new LibFactory(yosys, f);
        }
        return null;
    }

    private static String readMemId(CellParams p) {
        if (p instanceof GenericCellParams g) {
            Object v = g.asMap().get("MEMID");
            if (v == null) return null;
            String s = String.valueOf(v).trim();
            // Yosys suele anteponer "\" a los IDs RTLIL
            if (s.startsWith("\\")) s = s.substring(1);
            return s;
        }
        return null;
    }

    private static int ceilLog2(int n) {
        if (n <= 1) return 1;
        int k = 32 - Integer.numberOfLeadingZeros(n - 1);
        return Math.max(k, 1);
    }
}
