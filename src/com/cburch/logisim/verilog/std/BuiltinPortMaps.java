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

    private static final Map<String, Map<String,Integer>> BY_NAME = new HashMap<>();
    private static final Map<String, PortIndexResolver>   RESOLVER_BY_NAME = new HashMap<>();

    private static String keyOf(String libName, String compDisplayName) {
        return (libName == null ? "" : libName) + "::" + (compDisplayName == null ? "" : compDisplayName);
    }

    // ===== API de registro =====
    public static void registerByName(String libName, String compDisplayName,
                                      Map<String,Integer> nameToOrdinal) {
        BY_NAME.put(keyOf(libName, compDisplayName), Map.copyOf(nameToOrdinal));
    }

    public static void registerResolverByName(String libName, String compDisplayName,
                                              PortIndexResolver resolver) {
        RESOLVER_BY_NAME.put(keyOf(libName, compDisplayName), resolver);
    }

    public static Map<String,Integer> forFactory(Library lib, ComponentFactory f, Component instanceOrNull) {
        String key = keyOf(lib != null ? lib.getDisplayName() : "",
                f   != null ? f.getDisplayGetter().get() : "");
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

    public static Map<String,Integer> forComparator(Library lib, ComponentFactory f, Component instanceOrNull,
                                                    ComparatorOut out) {
        Map<String,Integer> base = forFactory(lib, f, instanceOrNull);
        // fallback razonable si no hubiera registro previo
        LinkedHashMap<String,Integer> m = new LinkedHashMap<>(base.isEmpty()
                ? Map.of("A", 0, "B", 1, "GT", 2, "EQ", 3, "LT", 4)
                : base);

        int yIdx;
        switch (out) {
            case EQ -> yIdx = m.getOrDefault("EQ", 3);
            case LT -> yIdx = m.getOrDefault("GT", 2);
            case GT -> yIdx = m.getOrDefault("LT", 4);
            default -> yIdx = m.getOrDefault("EQ", 3);
        }
        m.put("Y", yIdx); // alias lógico para cablear “Y” del JSON al pin correcto
        return m;
    }

    private BuiltinPortMaps() {}
}

