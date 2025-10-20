package com.cburch.logisim.verilog.comp.impl;

import com.cburch.logisim.verilog.comp.CellFactoryRegistry;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysModuleDTO;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.layout.ModuleNetIndex;

import java.util.*;

/**
* Builds VerilogModuleImpl from a YosysModuleDTO.
* Does not wire anything here; only sets up ports + cells.
* The indexes (nets/memories) are built on-demand with helpers.
*/
public final class VerilogModuleBuilder {
    private final CellFactoryRegistry registry;

    public VerilogModuleBuilder(CellFactoryRegistry registry) {
        this.registry = Objects.requireNonNull(registry);
    }

    /**
     * Construye un VerilogModuleImpl desde un DTO (sin wiring).
     *
     * @param dto DTO del módulo Yosys
     * @return Módulo Verilog construido
     */
    public VerilogModuleImpl buildModule(YosysModuleDTO dto) {
        VerilogModuleImpl mod = new VerilogModuleImpl(dto.name());

        // Puertos del módulo (compacto: int[] netIds)
        YosysModuleDTO.readModulePorts(dto.moduleNode().path("ports"), mod);

        // Netnames (nombre → {bits, hide_name})
        YosysModuleDTO.readNetnames(dto.moduleNode().path("netnames"), mod);

        // Celdas (vía factories)
        dto.cells().forEach(c -> {
            VerilogCell cell = registry.createCell(
                    c.name(),
                    c.typeId(),
                    c.parameters(),
                    c.attributes(),
                    c.portDirections(),
                    c.connections()
            );
            mod.addCell(cell);
        });

        return mod;
    }

    /** Índice de nets LAZY para cablear/exportar y luego descartar.
     *
     * @param mod Módulo a indexar
     * @return Índice de nets del módulo
     */
    public ModuleNetIndex buildNetIndex(VerilogModuleImpl mod) {
        return new ModuleNetIndex(mod.cells(), mod.ports());
    }

    /** Índice de memorias LAZY (agrupa por MEMID). */
    public MemoryIndex buildMemoryIndex(VerilogModuleImpl mod) { return new MemoryIndex(mod.cells()); }
}
