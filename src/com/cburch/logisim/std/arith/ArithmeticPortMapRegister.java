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

        BuiltinPortMaps.registerByName(arithLib.getName(), "Adder",
                Map.of("A", Adder.IN0, "B", Adder.IN1, "Y", Adder.OUT, "CIN", Adder.C_IN, "COUT", Adder.C_OUT));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Subtractor",
                Map.of("A", Subtractor.IN0, "B", Subtractor.IN1, "Y", Subtractor.OUT, "BIN", Subtractor.B_IN, "BOUT", Subtractor.B_OUT));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Multiplier",
                Map.of("A", Multiplier.IN0, "B", Multiplier.IN1, "Y", Multiplier.OUT, "CIN", Multiplier.C_IN, "COUT", Multiplier.C_OUT));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Divider",
                Map.of("A", Divider.IN0, "B", Divider.IN1, "Y", Divider.OUT, "REM", Divider.REM));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Comparator",
                Map.of("A", Comparator.IN0, "B", Comparator.IN1, "GT", Comparator.GT, "EQ", Comparator.EQ, "LT", Comparator.LT));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Negator",
                Map.of("A", Negator.IN, "Y", Negator.OUT));
        BuiltinPortMaps.registerByName(arithLib.getName(), "Shifter",
                Map.of("A", Shifter.IN0, "B", Shifter.IN1, "Y", Shifter.OUT));
    }
}
