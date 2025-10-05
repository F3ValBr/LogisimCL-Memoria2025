package com.cburch.logisim.verilog.std;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.instance.PortGeom;

public final class InstanceHandle {
    public final Component component;
    public final PortGeom ports;           // <-- info geométrica de puertos (por ordinal)

    public InstanceHandle(Component c, PortGeom p) {
        this.component = c;
        this.ports = p;
    }
}
