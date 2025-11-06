package com.cburch.logisim.std.plexers;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.LinkedHashMap;
import java.util.Map;

public class PlexersPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library plexersLib = lf.getLibrary("Plexers");
        if (plexersLib == null) return;

        BuiltinPortMaps.registerResolverByName(plexersLib.getName(), "Multiplexer",
                PlexersPortMapRegister::resolveMultiplexerPorts);
        BuiltinPortMaps.registerResolverByName(plexersLib.getName(), "Demultiplexer",
                PlexersPortMapRegister::resolveDemultiplexerPorts);
    }

    private static Map<String,Integer> resolveMultiplexerPorts(Component component) {
        AttributeSet as = component.getAttributeSet();

        // Leer select width y enable
        BitWidth selBw = as.getValue(Plexers.ATTR_SELECT);
        int selW = (selBw == null ? 1 : Math.max(1, selBw.getWidth()));
        int inputs = 1 << selW;
        boolean enable = Boolean.TRUE.equals(as.getValue(Plexers.ATTR_ENABLE));

        // Índices según Multiplexer.updatePorts(...)
        int sIdx  = inputs;
        int enIdx = enable ? inputs + 1 : -1;
        int yIdx  = enable ? inputs + 2 : inputs + 1;

        // Mapa de nombres -> índices
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();

        // Entradas: usar letras A, B, C, ... Z, AA, AB, etc.
        for (int i = 0; i < inputs; i++) {
            m.put(indexToLetter(i), i);
        }

        m.put("S", sIdx);
        if (enable) m.put("EN", enIdx);
        m.put("Y", yIdx);

        // Aliases para compatibilidad
        m.putIfAbsent("A", 0);
        if (inputs > 1) m.putIfAbsent("B", 1);

        return m;
    }

    private static Map<String,Integer> resolveDemultiplexerPorts(Component comp) {
        AttributeSet attrs = comp.getAttributeSet();

        // Nº de salidas = 2^(#select bits)
        BitWidth selW = attrs.getValue(Plexers.ATTR_SELECT);
        int outputs = 1 << selW.getWidth();

        boolean enable = Boolean.TRUE.equals(attrs.getValue(Plexers.ATTR_ENABLE));

        // Índices según updatePorts(...) del Demultiplexer:
        // [0..outputs-1] = salidas
        int IDX_SEL = outputs;                 // select
        LinkedHashMap<String, Integer> m = getStringIntegerLinkedHashMap(enable, outputs, IDX_SEL);

        // Alias de compatibilidad (si algún netlist dice solo "Y", lo
        // apuntamos a Y0 para no reventar; mejor que fallar).
        m.putIfAbsent("Y", 0);
        m.putIfAbsent("y", 0);

        return m;
    }

    private static LinkedHashMap<String, Integer> getStringIntegerLinkedHashMap(boolean enable, int outputs, int IDX_SEL) {
        Integer IDX_EN = enable ? outputs +1 : null; // enable si existe
        int IDX_A   = outputs + (enable ? 2 : 1);   // entrada de datos (último)

        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();

        // Entrada de datos
        m.put("A", IDX_A);
        m.put("a", IDX_A);
        m.put("in", IDX_A);
        m.put("dataIn", IDX_A);

        // Select
        m.put("S", IDX_SEL);
        m.put("s", IDX_SEL);
        m.put("SEL", IDX_SEL);
        m.put("select", IDX_SEL);

        // Enable (si existe)
        if (IDX_EN != null) {
            m.put("EN", IDX_EN); m.put("en", IDX_EN);
            m.put("ENABLE", IDX_EN); m.put("enable", IDX_EN);
        }

        // Salidas: Y0..Y{N-1} (y alias con corchetes)
        for (int i = 0; i < outputs; i++) {
            m.put("Y" + i, i);
            m.put("y" + i, i);
            m.put("Y[" + i + "]", i);
            m.put("y[" + i + "]", i);
        }
        return m;
    }

    /** Convierte un índice a una letra tipo Excel: 0→A, 1→B, ..., 25→Z, 26→AA, 27→AB, etc. */
    private static String indexToLetter(int index) {
        StringBuilder sb = new StringBuilder();
        int n = index;
        do {
            int rem = n % 26;
            sb.insert(0, (char) ('A' + rem));
            n = n / 26 - 1;
        } while (n >= 0);
        return sb.toString();
    }
}
