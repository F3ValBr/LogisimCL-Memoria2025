package com.cburch.logisim.verilog.std;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.std.PortMapRegister;
import com.cburch.logisim.tools.Library;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class BuiltinPortMaps {
    public interface PortIndexResolver {
        Map<String,Integer> resolve(Component component);
    }

    public enum ComparatorOut { EQ, LT, GT }
    public enum DividerOut    { QUOT, REM }

    private static final Map<String, Map<String,Integer>> BY_NAME = new HashMap<>();
    private static final Map<String, PortIndexResolver>   RESOLVER_BY_NAME = new HashMap<>();

    private static String keyOf(String libName, String componentName) {
        return (libName == null ? "" : libName) + "::" + (componentName == null ? "" : componentName);
    }

    // ===== API de registro =====
    public static void registerByName(String libName, String componentName,
                                      Map<String,Integer> nameToOrdinal) {
        BY_NAME.put(keyOf(libName, componentName), Map.copyOf(nameToOrdinal));
    }

    public static void registerResolverByName(String libName, String componentName,
                                              PortIndexResolver resolver) {
        RESOLVER_BY_NAME.put(keyOf(libName, componentName), resolver);
    }

    public static Map<String,Integer> forFactory(Library lib, ComponentFactory f, Component instanceOrNull) {
        String key = keyOf(lib != null ? lib.getName() : "",
                f   != null ? f.getName() : "");
        PortIndexResolver dyn = RESOLVER_BY_NAME.get(key);
        if (dyn != null && instanceOrNull != null) return dyn.resolve(instanceOrNull);
        return BY_NAME.getOrDefault(key, Map.of());
    }

    // ===== Bootstrap extensible: cada librería propia se auto-registra =====
    public static synchronized void initOnce(LogisimFile lf, List<PortMapRegister> registers) {
        if (lf == null || registers == null) return;
        for (PortMapRegister r : registers) {
            try { r.register(lf); } catch (Throwable t) {
                System.err.println("PortMapRegister error: " + t.getMessage());
            }
        }
    }

    // === Comparadores ===
    public static Map<String,Integer> forComparator(Library lib, ComponentFactory f, Component instanceOrNull,
                                                    ComparatorOut out) {
        Map<String,Integer> base = forFactory(lib, f, instanceOrNull);
        // fallback razonable si no hubiera registro previo
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>(base.isEmpty()
                ? Map.of("A", 0, "B", 1, "GT", 2, "EQ", 3, "LT", 4)
                : base);

        int yIdx;
        switch (out) {
            case GT -> yIdx = m.getOrDefault("GT", 2);
            case EQ -> yIdx = m.getOrDefault("EQ", 3);
            case LT -> yIdx = m.getOrDefault("LT", 4);
            default -> yIdx = m.getOrDefault("EQ", 3);
        }
        m.put("Y", yIdx); // alias lógico para cablear “Y” del JSON al pin correcto
        return m;
    }

    // === Divisores y módulos ===
    public static Map<String,Integer> forDivider(Library lib, ComponentFactory f, Component instanceOrNull,
                                                 DividerOut out) {
        Map<String,Integer> base = forFactory(lib, f, instanceOrNull);
        // Fallback estándar: A=0, B=1, QUOT=2, REM=4
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>(base.isEmpty()
                ? Map.of("A", 0, "B", 1, "QUOT", 2, "REM", 4)
                : base);

        int yIdx = (out == DividerOut.REM)
                ? m.getOrDefault("REM", 4)
                : m.getOrDefault("QUOT", 2);

        m.put("Y", yIdx); // alias lógico para el puerto “Y” del JSON
        return m;
    }

    private BuiltinPortMaps() {}
}

