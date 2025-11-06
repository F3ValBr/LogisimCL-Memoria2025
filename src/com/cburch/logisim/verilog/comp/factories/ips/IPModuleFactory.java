package com.cburch.logisim.verilog.comp.factories.ips;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.VerilogCellFactory;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.factories.ModuleInstanceFactory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.specs.GenericCellAttribs;
import com.cburch.logisim.verilog.comp.specs.ips.KnownIP;
import com.cburch.logisim.verilog.comp.specs.ips.RamIPParams;
import com.cburch.logisim.verilog.comp.specs.ips.RomIPParams;

import java.util.List;
import java.util.Map;
import java.util.Optional;

public final class IPModuleFactory extends AbstractVerilogCellFactory implements VerilogCellFactory {

    /** Devuelve true si el par (typeId, attrs) describe una IP conocida. */
    public static boolean isIpTypeId(String typeId) {
        return resolveKind(typeId).isPresent();
    }

    /** Intenta resolver el tipo de IP desde typeId o del atributo "ip_kind". */
    private static Optional<KnownIP> resolveKind(String typeId) {
        return KnownIP.from(typeId);
    }

    @Override
    public VerilogCell create(String name, String typeId,
                              Map<String,String> parameters,
                              Map<String,Object> attributes,
                              Map<String,String> portDirections,
                              Map<String, List<Object>> connections) {

        // Resuelve el tipo de IP (RAM/ROM). Si no es IP, delega a módulo normal.
        Optional<KnownIP> k = resolveKind(typeId);
        if (k.isEmpty()) {
            // No es IP reconocida → tratar como instancia de módulo
            return new ModuleInstanceFactory()
                    .create(name, typeId, parameters, attributes, portDirections, connections);
        }

        final CellType ct = CellType.fromYosys(typeId);
        final GenericCellAttribs attribs = new GenericCellAttribs(attributes);

        switch (k.get()) {
            case RAM -> {
                RamIPParams p = RamIPParams.from(parameters);
                VerilogCell cell = new WordLvlCellImpl(name, ct, p, attribs);
                buildEndpoints(cell, portDirections, connections);
                RamIPParams.validatePorts(cell, p);
                return cell;
            }
            case ROM -> {
                RomIPParams p = RomIPParams.from(parameters);
                VerilogCell cell = new WordLvlCellImpl(name, ct, p, attribs);
                buildEndpoints(cell, portDirections, connections);
                RomIPParams.validatePorts(cell, p);
                return cell;
            }
        }

        // Salvaguarda, aunque no debería llegar aquí
        return new ModuleInstanceFactory()
                .create(name, typeId, parameters, attributes, portDirections, connections);
    }
}
