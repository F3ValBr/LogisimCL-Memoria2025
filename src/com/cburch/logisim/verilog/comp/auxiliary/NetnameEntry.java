package com.cburch.logisim.verilog.comp.auxiliary;

public record NetnameEntry(
        String name,
        int[] bits,          // IDs de nets en orden
        boolean hideName     // opcional según Yosys (0/1)
) { }
