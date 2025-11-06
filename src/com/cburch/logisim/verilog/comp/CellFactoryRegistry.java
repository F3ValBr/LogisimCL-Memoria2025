package com.cburch.logisim.verilog.comp;

import com.cburch.logisim.verilog.comp.factories.ModuleInstanceFactory;
import com.cburch.logisim.verilog.comp.factories.gatelvl.*;
import com.cburch.logisim.verilog.comp.factories.ips.IPModuleFactory;
import com.cburch.logisim.verilog.comp.factories.wordlvl.*;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.ModuleAttribs;
import com.cburch.logisim.verilog.comp.specs.gatelvl.RegisterGateOp;
import com.cburch.logisim.verilog.comp.specs.gatelvl.GateOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.*;

import java.util.*;

public class CellFactoryRegistry {
    private final Map<String, VerilogCellFactory> overrides = new HashMap<>();

    private final VerilogCellFactory ipFactory     = new IPModuleFactory();
    private final VerilogCellFactory unaryFactory  = new UnaryOpFactory();
    private final VerilogCellFactory binaryFactory = new BinaryOpFactory();
    private final VerilogCellFactory muxFactory    = new MuxOpFactory();
    private final VerilogCellFactory registerFactory = new RegisterOpFactory();
    private final VerilogCellFactory memoryFactory = new MemoryOpFactory();
    private final VerilogCellFactory gateFactory   = new GateOpFactory();
    private final VerilogCellFactory registerGateFactory = new RegisterGateOpFactory();
    private final VerilogCellFactory moduleFactory = new ModuleInstanceFactory();

    public void register(String typeId, VerilogCellFactory factory) {
        overrides.put(Objects.requireNonNull(typeId), Objects.requireNonNull(factory));
    }

    public VerilogCell createCell(String name, String typeId,
                                  Map<String,String> parameters,
                                  Map<String,Object> attributes,
                                  Map<String,String> portDirections,
                                  Map<String,List<Object>> connections) {

        // 0) Override explícito
        VerilogCellFactory f = overrides.get(typeId);
        if (f != null) {
            return f.create(name, typeId, parameters, attributes, portDirections, connections);
        }

        // Normaliza id para comparaciones
        String norm = normalizeId(typeId);

        // 1) IPs conocidas SIEMPRE primero (queremos mapear a nativo aunque haya module_not_derived)
        if (IPModuleFactory.isIpTypeId(norm)) {
            return ipFactory.create(name, typeId, parameters, attributes, portDirections, connections);
        }

        // 2) Gate-level ($_...)
        if (norm.startsWith("$_")) {
            if (GateOp.isGateTypeId(typeId))
                return gateFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            if (RegisterGateOp.matchesRGOp(typeId))
                return registerGateFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            return moduleFactory.create(name, typeId, parameters, attributes, portDirections, connections);
        }

        // 3) Word-level ($...)
        if (norm.startsWith("$")) {
            if (UnaryOp.isUnaryTypeId(typeId))
                return unaryFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            if (BinaryOp.isBinaryTypeId(typeId))
                return binaryFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            if (MuxOp.isMuxTypeId(typeId))
                return muxFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            if (RegisterOp.isRegisterTypeId(typeId))
                return registerFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            if (MemoryOp.isMemoryTypeId(typeId))
                return memoryFactory.create(name, typeId, parameters, attributes, portDirections, connections);
            return moduleFactory.create(name, typeId, parameters, attributes, portDirections, connections);
        }

        // 4) Si viene marcado como módulo no derivado → módulo
        if (isModuleNotDerived(attributes)) {
            return moduleFactory.create(name, typeId, parameters, attributes, portDirections, connections);
        }

        // 5) Sin typeId → módulo
        if (typeId == null || typeId.isBlank()) {
            return moduleFactory.create(name, "<unknown>", parameters, attributes, portDirections, connections);
        }

        // 6) Módulo de usuario
        return moduleFactory.create(name, typeId, parameters, attributes, portDirections, connections);
    }

    /* ===== Helpers ===== */

    private static String normalizeId(String id) {
        return id == null ? "" : id.trim().toLowerCase();
    }

    private boolean isModuleNotDerived(Map<String,Object> attrs) {
        return new ModuleAttribs(attrs).isModuleNotDerived();
    }
}
