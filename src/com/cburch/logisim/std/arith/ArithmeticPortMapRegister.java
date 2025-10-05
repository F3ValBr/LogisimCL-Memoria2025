package com.cburch.logisim.std.arith;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.Map;

public final class ArithmeticPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library arithLib = lf.getLibrary("Arithmetic");
        if (arithLib == null) return;

        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Adder",
                Map.of("A", 0, "B", 1, "Y", 2, "CIN", 3, "COUT", 4));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Subtractor",
                Map.of("A", 0, "B", 1, "Y", 2, "BIN", 3, "BOUT", 4));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Multiplier",
                Map.of("A", 0, "B", 1, "Y", 2, "CIN", 3, "COUT", 4));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Divider",
                Map.of("A", 0, "B", 1, "Y", 2, "REM", 3));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Comparator",
                Map.of("A", 0, "B", 1, "GT", 2, "EQ", 3, "LT", 4));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Negator",
                Map.of("A", 0, "Y", 1));
        BuiltinPortMaps.registerByName(arithLib.getDisplayName(), "Shifter",
                Map.of("A", 0, "B", 1, "Y", 2));
    }
}
