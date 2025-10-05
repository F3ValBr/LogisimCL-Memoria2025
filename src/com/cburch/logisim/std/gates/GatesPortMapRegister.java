package com.cburch.logisim.std.gates;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.Map;

public final class GatesPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library gatesLib = lf.getLibrary("Gates");
        if (gatesLib == null) return;

        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "AND Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "NAND Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "OR Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "NOR Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "XOR Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "XNOR Gate",
                Map.of("Y", 0, "A", 1, "B", 2));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "NOT Gate",
                Map.of("Y", 0, "A", 1));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "Buffer",
                Map.of("Y", 0, "A", 1));
        BuiltinPortMaps.registerByName(gatesLib.getDisplayName(), "Controlled Buffer",
                Map.of("Y", 0, "A", 1, "EN", 2));

    }
}
