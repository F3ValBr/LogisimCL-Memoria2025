package com.cburch.logisim.std.gates;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;

import java.util.LinkedHashMap;
import java.util.Map;

public final class GatesPortMapRegister implements PortMapRegister {
    @Override
    public void register(LogisimFile lf) {
        Library gatesLib = lf.getLibrary("Gates");
        if (gatesLib == null) return;

        // Resolvedores genéricos para puertas con N entradas
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "AND Gate",
                GatesPortMapRegister::resolveNaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "NAND Gate",
                GatesPortMapRegister::resolveNaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "OR Gate",
                GatesPortMapRegister::resolveNaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "NOR Gate",
                GatesPortMapRegister::resolveNaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "XOR Gate",
                GatesPortMapRegister::resolveNaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "XNOR Gate",
                GatesPortMapRegister::resolveNaryGate);

        // Unarias
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "NOT Gate",
                GatesPortMapRegister::resolveUnaryGate);
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "Buffer",
                GatesPortMapRegister::resolveUnaryGate);

        // Buffer controlado (Y, A, EN) => 3 pines fijos
        BuiltinPortMaps.registerResolverByName(
                gatesLib.getName(), "Controlled Buffer",
                GatesPortMapRegister::resolveControlledBuffer);
    }

    /** Devuelve cantidad de ends de un componente sin asumir API extra. */
    private static int endCount(Component c) {
        int n = 0;
        while (true) {
            try {
                c.getEnd(n);
                n++;
            } catch (IndexOutOfBoundsException ex) {
                break;
            }
        }
        return n;
    }

    /** Mapea Y->0 y entradas A.. según cantidad real de inputs (N ends = 1 salida + (N-1) entradas). */
    private static Map<String,Integer> resolveNaryGate(Component c) {
        int nEnds = endCount(c);
        int nInputs = Math.max(0, nEnds - 1);

        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();
        // Salida
        m.put("Y", 0);
        m.put("y", 0);
        m.putIfAbsent("Y0", 0); // alias tolerante
        m.putIfAbsent("y0", 0);

        // Entradas A,B,C,... y alias INi/Di
        for (int i = 0; i < nInputs; i++) {
            int idx = 1 + i;
            char letter = (char) ('A' + i);
            String L = String.valueOf(letter);
            String l = L.toLowerCase();

            m.put(L, idx);
            m.put(l, idx);

            m.put("IN" + i, idx);
            m.put("in" + i, idx);
            m.put("D" + i, idx);
            m.put("d" + i, idx);
        }
        // Compat: si alguien dijo solo "A" y hay al menos 1 entrada ya está cubierto.
        return m;
    }

    /** NOT/Buffer: Y->0, A->1 (y alias). */
    private static Map<String,Integer> resolveUnaryGate(Component c) {
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();
        m.put("Y", 0); m.put("y", 0); m.putIfAbsent("Y0", 0); m.putIfAbsent("y0", 0);

        int nEnds = endCount(c);
        int inIdx = (nEnds >= 2) ? 1 : Math.min(1, nEnds - 1); // robustez

        m.put("A", inIdx); m.put("a", inIdx);
        m.put("IN0", inIdx); m.put("in0", inIdx);
        m.put("D0", inIdx);  m.put("d0", inIdx);
        return m;
    }

    /** Controlled Buffer: Y->0, A->1, EN->2 (siempre 3 pines en Logisim-evolution). */
    private static Map<String,Integer> resolveControlledBuffer(Component c) {
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>();
        m.put("Y", 0); m.put("y", 0); m.putIfAbsent("Y0", 0); m.putIfAbsent("y0", 0);

        m.put("A", 1); m.put("a", 1);
        m.put("IN0", 1); m.put("in0", 1);
        m.put("D0", 1);  m.put("d0", 1);

        m.put("EN", 2); m.put("en", 2);
        m.put("ENABLE", 2); m.put("enable", 2);
        return m;
    }
}

