package com.cburch.logisim.instance;

import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.Location;

import java.util.*;

/**
 * Resuelve ubicaciones de puertos para una instancia concreta.
 * - Caso simple: todos los nombres pertenecen al mismo componente (of()).
 * - Caso compuesto: cada nombre puede pertenecer a un componente distinto (composite()).
 * Usa únicamente APIs públicas: Component.getEnds() → EndData[index].getLocation().
 */
public final class PortGeom {

    /** Par (componente, índice-de-puerto) que identifica un pin físico. */
    public static final class Target {
        public final Component component;
        public final int index;
        public Target(Component component, int index) {
            this.component = Objects.requireNonNull(component, "component");
            this.index = index;
        }
    }

    /* ===== estado ===== */
    private final Component primary;            // componente “principal” (opcional)
    private final EndData[] primaryEnds;        // ends del componente principal
    private final Map<String,Integer> primaryNameToIdx; // nombre→índice en el primary (opcional)

    // nombre lógico → (componente, índice)
    private final Map<String, Target> byName;

    private PortGeom(Component primary,
                     EndData[] primaryEnds,
                     Map<String,Integer> primaryNameToIdx,
                     Map<String, Target> composed) {
        this.primary = primary;
        this.primaryEnds = (primaryEnds != null) ? primaryEnds : new EndData[0];
        this.primaryNameToIdx = (primaryNameToIdx != null) ? Map.copyOf(primaryNameToIdx) : Map.of();
        this.byName = (composed != null) ? Map.copyOf(composed) : Map.of();
    }

    /* ===== factories ===== */

    /** Caso sencillo: todos los nombres pertenecen al mismo componente. */
    public static PortGeom of(Component comp, Map<String,Integer> nameToIdx) {
        Objects.requireNonNull(comp, "component");
        Objects.requireNonNull(nameToIdx, "nameToIdx");

        EndData[] ends = safeEnds(comp);

        Map<String, Target> m = new LinkedHashMap<>();
        for (var e : nameToIdx.entrySet()) {
            m.put(e.getKey(), new Target(comp, e.getValue()));
        }
        return new PortGeom(comp, ends, nameToIdx, m);
    }

    /**
     * Caso compuesto: cada nombre puede pertenecer a un componente distinto.
     * Ej.: "A","B" en comparator; "Y" en NOT/OR auxiliar.
     */
    public static PortGeom composite(Map<String, Target> mapping) {
        Objects.requireNonNull(mapping, "mapping");
        Component first = mapping.isEmpty() ? null : mapping.values().iterator().next().component;
        EndData[] ends = (first != null) ? safeEnds(first) : new EndData[0];
        return new PortGeom(first, ends, Collections.emptyMap(), mapping);
    }

    /** Helper: acceso al mapping compuesto (útil si luego querés depurar). */
    public Map<String, Target> mapping() { return byName; }

    /* ===== consultas ===== */

    /** Ubica por índice de puerto del componente “primario”. */
    public Location locateByIndex(int portIdx) {
        if (portIdx < 0 || portIdx >= primaryEnds.length) return null;
        EndData ed = primaryEnds[portIdx];
        return ed == null ? null : ed.getLocation();
    }

    /** Ubica por nombre lógico (sirve tanto para caso simple como compuesto). */
    public Location locateByName(String portName) {
        if (portName == null) return null;
        Target t = byName.get(portName);
        if (t == null) return null;
        EndData[] ends = safeEnds(t.component);
        if (t.index < 0 || t.index >= ends.length) return null;
        EndData ed = ends[t.index];
        return ed == null ? null : ed.getLocation();
    }

    /** Devuelve el índice (en el componente primario) de un nombre, o -1 si no existe. */
    public int primaryIndexOf(String portName) {
        Integer idx = primaryNameToIdx.get(portName);
        return (idx == null) ? -1 : idx;
    }

    /** Ancho del puerto por índice en el componente primario. */
    public int widthOfIndex(int portIdx) {
        if (portIdx < 0 || portIdx >= primaryEnds.length) return 1;
        EndData ed = primaryEnds[portIdx];
        return ed == null ? 1 : Math.max(1, ed.getWidth().getWidth());
    }

    /** Ancho del puerto por nombre (compuesto o simple). */
    public int widthOfName(String portName) {
        Target t = byName.get(portName);
        if (t == null) return 1;
        EndData[] ends = safeEnds(t.component);
        if (t.index < 0 || t.index >= ends.length) return 1;
        EndData ed = ends[t.index];
        return ed == null ? 1 : Math.max(1, ed.getWidth().getWidth());
    }

    /* ===== util ===== */

    private static EndData[] safeEnds(Component comp) {
        // En Logisim clásico, getEnds() devuelve EndData[] directamente.
        // Si tu fork devolviera una lista, ajusta aquí (pero evita usar clases no públicas).
        return comp.getEnds().toArray(new EndData[0]);
    }
}
