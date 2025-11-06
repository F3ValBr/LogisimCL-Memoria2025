package com.cburch.logisim.verilog.comp.specs;

import java.util.Map;

public final class ModuleAttribs extends GenericCellAttribs {

    public ModuleAttribs(Map<String, ?> raw) {
        super(raw);
    }

    /**
     * Indica si este módulo fue marcado por Yosys como "no derivado".
     * Yosys coloca el atributo "module_not_derived": "000...1".
     */
    public boolean isModuleNotDerived() {
        Object v = getRaw("module_not_derived"); // usa acceso directo al mapa crudo
        if (v == null) return false;

        if (v instanceof Boolean b) return b;
        if (v instanceof Number n)  return n.longValue() != 0L;

        String s = String.valueOf(v).trim();
        if (s.isEmpty()) return false;

        if ("1".equals(s) || "true".equalsIgnoreCase(s)) return true;
        if ("0".equals(s) || "false".equalsIgnoreCase(s)) return false;

        // Bitstring de 0/1 de Yosys
        if (s.matches("[01]+")) {
            return s.indexOf('1') >= 0;
        }

        // Hex o decimal
        try {
            if (s.startsWith("0x") || s.startsWith("0X")) {
                return Long.parseUnsignedLong(s.substring(2), 16) != 0L;
            }
            return Long.parseLong(s) != 0L;
        } catch (NumberFormatException ignore) {
            return false;
        }
    }

    /** Ubicación de origen en RTL (ej: "file.sv:13.19-13.65"). */
    public String source() {
        return getString("src", "unknown");
    }

    private Object getRaw(String key) {
        return super.asMap().get(key);
    }
}
