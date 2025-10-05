package com.cburch.logisim.std.plexers;

import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

public class PlexersPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library plexersLib = lf.getLibrary("Plexers");
        if (plexersLib == null) return;

        BuiltinPortMaps.registerByName(plexersLib.getDisplayName(), "Multiplexer",
                java.util.Map.of("A", 0, "B", 1, "S", 2, "Y", 3));
        BuiltinPortMaps.registerByName(plexersLib.getDisplayName(), "Demultiplexer",
                java.util.Map.of("A", 0, "S", 1, "Y", 2));
    }
}
