package com.cburch.logisim.std.yosys;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.LinkedHashMap;
import java.util.Map;

public class YosysComponentsPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library YCLib = lf.getLibrary("Yosys Components");
        if (YCLib == null) return;

        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Logical NOT Gate",
                java.util.Map.of("A", 0, "Y", 1));
        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Logical AND Gate",
                java.util.Map.of("A", 0, "B", 1, "Y", 2));
        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Logical OR Gate",
                java.util.Map.of("A", 0, "B", 1, "Y", 2));
        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Exponent",
                java.util.Map.of("A", 0, "Y", 1));
        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Bitwise Multiplexer",
                java.util.Map.of("A", 0, "B", 1, "S", 2, "Y", 3));
        BuiltinPortMaps.registerByName(YCLib.getDisplayName(), "Binary Multiplexer",
                java.util.Map.of("A", 0, "AX", 1, "S", 2, "Y", 3));
        BuiltinPortMaps.registerResolverByName(
                YCLib.getDisplayName(),
                "Priority Multiplexer",
                YosysComponentsPortMapRegister::resolvePriMuxPorts
        );
    }

    private static Map<String, Integer> resolvePriMuxPorts(Component comp) {
        // El orden en updatePorts es: A, B, (BX si hasExtra), S, Y
        // Por lo tanto, los índices quedan:
        // hasExtra=false → A=0, B=1, S=2, Y=3
        // hasExtra=true  → A=0, B=1, BX=2, S=3, Y=4

        int pinCount = comp.getEnds().size();
        boolean hasExtra = (pinCount == 5);

        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("A", 0);
        m.put("B", 1);
        if (hasExtra) {
            m.put("BX", 2);          // parte alta del bus B
            m.put("S",  3);
            m.put("Y",  4);
        } else {
            m.put("S",  2);
            m.put("Y",  3);
        }
        return m;
    }
}
