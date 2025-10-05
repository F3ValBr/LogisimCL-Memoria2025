package com.cburch.logisim.std.memory;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.Map;

public final class MemoryPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library memoryLib = lf.getLibrary("Memory");
        if (memoryLib == null) return;

        BuiltinPortMaps.registerResolverByName(memoryLib.getDisplayName(), "Register",
                MemoryPortMapRegister::resolveRegisterPorts);
        BuiltinPortMaps.registerByName(memoryLib.getDisplayName(), "RAM",
                Map.of("addr", 1, "dataIn", 2, "dataOut", 0, "we", 3, "clk", 4));
        BuiltinPortMaps.registerByName(memoryLib.getDisplayName(), "ROM",
                Map.of("addr", 1, "dataOut", 0, "clk", 2));
        BuiltinPortMaps.registerByName(memoryLib.getDisplayName(), "S-R Flip-Flop",
                Map.of("countOut", 0, "enable", 1, "load", 2, "clear", 3, "loadValue", 4, "clk", 5));
    }

    /** Replica la política de índices de tu Register.recomputePorts(). */
    private static Map<String,Integer> resolveRegisterPorts(Component component) {
        AttributeSet attrs = component.getAttributeSet();

        // Índices base (coinciden con tu implementación actual)
        final int OUT = 0; // Q
        final int IN  = 1; // D
        final int CK  = 2; // CLK

        // Lee atributos tal como los usa tu Register (¡sin cambiar Register!)
        AttributeOption resetType = attrs.getValue(Register.RESET_TYPE);
        boolean hasRst = resetType != Register.NO_RESET;

        Boolean hasEnObj = attrs.getValue(Register.HAS_EN);
        boolean hasEn = (hasEnObj != null && hasEnObj);

        Integer RST = null, EN = null;
        if (hasRst) {
            RST = 3;
            EN  = hasEn ? 4 : null;
        } else {
            EN  = hasEn ? 3 : null;
        }

        // Construye nombre → índice sólo con los presentes
        java.util.LinkedHashMap<String,Integer> m = new java.util.LinkedHashMap<>();
        m.put("Q",   OUT);
        m.put("D",   IN);
        m.put("CLK", CK);
        if (RST != null) {
            m.put("RST", RST);
            m.put("ARST", RST); // Alias
            m.put("SRST", RST); // Alias
        }
        if (EN  != null) m.put("EN",  EN);
        return m;
    }
}
