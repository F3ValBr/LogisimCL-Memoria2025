package com.cburch.logisim.verilog.comp.auxiliary;

import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;

public final class ModulePort {
    public static final int CONST_0 = -1;
    public static final int CONST_1 = -2;
    public static final int CONST_X = -3;

    private final String name;     // puedes intern() si hay muchos iguales
    private final PortDirection dir;
    private final int[] netIds;    // LSB→MSB; >=0 net real, <0 constante

    public ModulePort(String name, PortDirection dir, int[] netIds) {
        this.name = name.intern();
        this.dir  = dir;
        this.netIds = netIds;
    }

    public String name()          { return name; }
    public PortDirection direction()  { return dir; }
    public int[] netIds()        { return netIds; }
    public int width()            { return netIds.length; }
    public int netIdAt(int i)     { return netIds[i]; }

    public boolean isConst0(int i){ return netIds[i] == CONST_0; }
    public boolean isConst1(int i){ return netIds[i] == CONST_1; }
    public boolean isConstX(int i){ return netIds[i] == CONST_X; }

    @Override public String toString() {
        return name + ":" + dir + "[" + width() + "]";
    }
}
