package com.cburch.logisim.std.memory;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.LinkedHashMap;
import java.util.Map;

public final class MemoryPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library memoryLib = lf.getLibrary("Memory");
        if (memoryLib == null) return;

        BuiltinPortMaps.registerResolverByName(memoryLib.getName(), "Register",
                MemoryPortMapRegister::resolveRegisterPorts);
        BuiltinPortMaps.registerResolverByName(memoryLib.getName(), "RAM",
                MemoryPortMapRegister::resolveRamPorts);
        BuiltinPortMaps.registerByName(memoryLib.getName(), "ROM",
                Map.of("$1", Mem.ADDR, "addr", Mem.ADDR, "A", Mem.ADDR, "RD_ADDR", Mem.ADDR,
                        "$2", Mem.DATA, "dataOut", Mem.DATA, "Q", Mem.DATA, "RD_DATA", Mem.DATA
                ));
        BuiltinPortMaps.registerByName(memoryLib.getName(), "S-R Flip-Flop",
                Map.of("countOut", 0, "enable", 1, "load", 2, "clear", 3, "loadValue", 4, "clk", 5));
    }

    /* ============================
       Resolver para Register
       ============================ */
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

        AttributeOption triggerType = attrs.getValue(StdAttr.TRIGGER);
        boolean isLatch = (triggerType == StdAttr.TRIG_HIGH || triggerType == StdAttr.TRIG_LOW);

        Integer RST = null, EN = null;
        if (hasRst) {
            RST = 3;
            EN  = hasEn ? 4 : null;
        } else {
            EN  = hasEn ? 3 : null;
        }

        // Construye nombre → índice sólo con los presentes
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();

        // Siempre presentes
        m.put("Q", OUT);  m.put("q", OUT);
        m.put("D", IN);   m.put("d", IN);

        if (isLatch) {
            m.put("EN", CK);  m.put("en", CK);
            m.put("ENABLE", CK); m.put("enable", CK);
            m.put("E", CK);
        } else {
            // ---- FLIP-FLOP: reloj verdadero en CLK ----
            m.put("CLK", CK); m.put("clk", CK); m.put("C", CK);

            // Si además hay EN, expónlo con sus aliases habituales
            if (EN != null) {
                m.put("EN", EN); m.put("en", EN);
                m.put("ENABLE", EN); m.put("enable", EN);
                m.put("E", EN);
            }
        }

        // Reset (si existe), con varios alias típicos
        if (RST != null) {
            m.put("RST", RST);  m.put("rst", RST);
            m.put("ARST", RST); m.put("arst", RST);
            m.put("SRST", RST); m.put("srst", RST);
            m.put("CLR", RST);  m.put("clr",  RST);
            m.put("R", RST);
        }
        return m;
    }

    /* ============================
       Resolver para RAM (DINÁMICO)
       ============================ */
    private static Map<String,Integer> resolveRamPorts(Component component) {
        AttributeSet attrs = component.getAttributeSet();

        int idx = Mem.MEM_INPUTS;

        Object bus = attrs.getValue(Ram.ATTR_BUS);
        boolean asynch   = Ram.BUS_ASYNCH.equals(bus);
        boolean separate = Ram.BUS_SEPARATE.equals(bus);
        boolean clear    = Boolean.TRUE.equals(attrs.getValue(Ram.CLEAR_PIN));

        final int OE  = idx++;
        final int CLR = clear   ? idx++ : -1;
        final int CLK = !asynch ? idx++ : -1;
        final int WE  = separate ? idx++ : -1;
        final int DIN = separate ? idx++ : -1;

        // ---- Mapear nombres lógicos → índices reales ----
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();

        // Estándar de Mem:
        m.put("$3", Mem.ADDR);  m.put("addr",   Mem.ADDR);   m.put("A",    Mem.ADDR);   m.put("RD_ADDR", Mem.ADDR);
        m.put("$5", Mem.DATA);  m.put("dataOut",Mem.DATA);   m.put("Q",    Mem.DATA);   m.put("RD_DATA", Mem.DATA);

        // Chip select y output enable siempre existen lógicamente
        m. put("cs", Mem.CS);   m.put("CS", Mem.CS);
        m.put("oe", OE);    m.put("OE", OE);    m.put("RD_EN", OE);

        // CLK sólo si existe (no asíncrono)
        if (CLK >= 0) { m.put("$1", CLK); m.put("clk", CLK); m.put("CLK", CLK); m.put("WR_CLK", CLK); }

        // RESET/CLEAR si existe
        if (CLR >= 0) {
            m.put("clr", CLR); m.put("CLR", CLR);
            m.put("rst", CLR); m.put("RST", CLR);
        }

        // Bus de datos de entrada:
        if (separate && DIN >= 0) {
            m.put("$4", DIN); m.put("dataIn", DIN); m.put("D", DIN); m.put("WR_DATA", DIN);
        } else {
            // En bus combinado no hay DIN separado; permite alias por compatibilidad:
            m.put("$4", Mem.DATA); m.put("dataIn", Mem.DATA); m.put("D",  Mem.DATA); m.put("WR_DATA", Mem.DATA);
        }

        // WE sólo si bus separado; en bus combinado el “escritura” se controla con OE
        if (separate && WE >= 0) {
            m.put("$2", WE); m.put("we", WE); m.put("WE", WE); m.put("WR_EN", WE);
        }

        // Algunos alias adicionales por robustez
        m.putIfAbsent("DO", Mem.DATA);
        if (separate && DIN >= 0) m.putIfAbsent("DI", DIN);

        return m;
    }
}
