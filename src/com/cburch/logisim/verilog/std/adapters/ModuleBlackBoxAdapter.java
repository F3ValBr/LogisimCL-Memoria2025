package com.cburch.logisim.verilog.std.adapters;


import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.Component;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.PortEndpoint;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.std.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import static com.cburch.logisim.verilog.file.importer.ImporterUtils.Geom.snap;
import static com.cburch.logisim.verilog.file.importer.VerilogJsonImporter.*;

public final class ModuleBlackBoxAdapter extends AbstractComponentAdapter {

    private final ModuleMaterializer materializer;

    // Úsalo cuando aún no tienes materializer (no hace nada)
    public ModuleBlackBoxAdapter() {
        this.materializer = ModuleMaterializer.noop();
    }

    // Úsalo cuando SÍ tienes materializer (el que pasas en register)
    public ModuleBlackBoxAdapter(ModuleMaterializer materializer) {
        this.materializer = (materializer != null) ? materializer : ModuleMaterializer.noop();
    }

    @Override public boolean accepts(CellType t) { return true; } // fallback universal

    @Override
    public InstanceHandle create(Project proj, Circuit hostCirc, Graphics gMaybeNull, VerilogCell cell, Location where) {
        try {
            Graphics g = gMaybeNull;
            if (g == null) g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();

            final String modName = safeName(cell.type().typeId());

            // 1) Intenta materializar desde FS (si está configurado)
            if (materializer != null) {
                try { materializer.ensureModule(proj, modName); } catch (Throwable ignore) {}
            }

            // 2) Obtén/crea el circuito del “black box”
            Circuit newCirc = findCircuit(proj.getLogisimFile(), modName);
            boolean createdNow = false;
            if (newCirc == null) {
                newCirc = new Circuit(modName);
                proj.doAction(LogisimFileActions.addCircuit(newCirc));
                createdNow = true;
            }

            // Evitar recursión/ciclos
            if (newCirc == hostCirc) return null;
            if (!proj.getDependencies().canAdd(hostCirc, newCirc)) return null;

            // 3) Si el circuito está vacío, crear pines de fallback: mitad IN, mitad OUT por orden alfabético.
            List<String> orderForNameToIdx = null;
            if (!circuitHasAnyComponent(newCirc) && createdNow) {
                // Primero: intentar desde endpoints()
                Map<String, PortDirection> dirs = directionsFromEndpoints(cell);

                if (!dirs.isEmpty()) {
                    orderForNameToIdx = populatePinsFromDirections(proj, newCirc, cell, dirs);
                }
                // Si no salió nada útil de endpoints (o devolvió null), caer al fallback 50/50.
                if (orderForNameToIdx == null) {
                    orderForNameToIdx = populateFallbackPins(proj, newCirc, cell);
                }
            }

            // 4) Instanciar el subcircuito
            InstanceFactory factory = newCirc.getSubcircuitFactory();
            AttributeSet attrs = factory.createAttributeSet();
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) {}
            try { attrs.setValue(CircuitAttributes.LABEL_LOCATION_ATTR, Direction.NORTH); } catch (Exception ignore) {}

            // 5) Añadir al circuito
            Component comp = addComponent(proj, hostCirc, g, factory, where, attrs);

            // 6) name->index coherente para creación de pines
            Map<String, Integer> nameToIdx = new LinkedHashMap<>();
            if (orderForNameToIdx != null) {
                int k = 0;
                for (String pname : orderForNameToIdx) nameToIdx.put(pname, k++);
            } else {
                // si ya existían pines (materializer real), usa orden estable por nombre
                List<String> portNames = new ArrayList<>(cell.getPortNames());
                portNames.sort(String::compareTo);
                int k = 0;
                for (String pname : portNames) nameToIdx.put(pname, k++);
            }

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            g.dispose();
            return new InstanceHandle(comp, pg);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir subcircuito: " + e.getMessage(), e);
        }
    }

    // === helpers de grid/espaciado ===
    private static final int MIN_GAP = 5 * GRID; // separación mínima entre pines
    private static final int TOP_PAD = 2 * GRID; // margen superior de columna
    private static final int BOT_PAD = 2 * GRID; // margen inferior de columna

    private static int roundUpToGrid(int v) {
        int g = Math.max(1, GRID);
        int r = v % g;
        return (r == 0) ? v : (v + (g - r));
    }

    /** Calcula un step vertical ≥ MIN_GAP y múltiplo del grid. */
    private static int calcStep(int span, int count) {
        if (count <= 0) return MIN_GAP;
        // espacio disponible para separaciones entre (count+1) huecos (con márgenes)
        int base = span / (count + 1);
        int step = Math.max(MIN_GAP, base);
        return roundUpToGrid(step);
    }

    /** Y central del i-ésimo pin (1..count) dentro de una columna con márgenes/step dados. */
    private static int yAtIndex(int startY, int step, int index1based) {
        return startY + index1based * step;
    }


    /* -------------------- Distribución de pins de entrada y salida -------------------- */

    private List<String> populatePinsFromDirections(Project proj, Circuit newCirc,
                                                    VerilogCell cell,
                                                    Map<String, PortDirection> dirs) throws CircuitException {
        // Orden estable por nombre del puerto
        List<String> names = new ArrayList<>(dirs.keySet());
        Collections.sort(names);

        List<String> ins = new ArrayList<>();
        List<String> outs = new ArrayList<>();
        List<String> inouts = new ArrayList<>();

        for (String p : names) {
            switch (dirs.getOrDefault(p, PortDirection.UNKNOWN)) {
                case INPUT  -> ins.add(p);
                case OUTPUT -> outs.add(p);
                case INOUT  -> inouts.add(p);
                default     -> { /* ignora unknown */ }
            }
        }

        if (ins.isEmpty() && outs.isEmpty() && inouts.isEmpty()) {
            return null; // nada útil → fallback
        }

        // === Layout
        final int total = ins.size() + outs.size() + inouts.size();

        // Altura de la “pista” vertical: proporcional a cantidad, pero con pad, y múltiplo del grid
        int rawSpan = TOP_PAD + (total * MIN_GAP) + BOT_PAD;
        final int spanY = roundUpToGrid(Math.max(100, rawSpan));

        // Columnas (ajusta si quieres abrir más el ancho)
        final int leftX  = snap(MIN_X + 40);
        final int rightX = snap(MIN_X + 240);

        // Conteos por columna (IN y INOUT a la izquierda, OUT a la derecha)
        final int nLeft  = ins.size() + inouts.size();
        final int nRight = outs.size();

        // Steps verticales (si una columna está vacía, usa MIN_GAP para evitar /0)
        final int stepLeft  = calcStep(spanY,  Math.max(1, nLeft));
        final int stepRight = calcStep(spanY,  Math.max(1, nRight));

        // Y base (parte superior de la pista)
        final int startYLeft  = snap(MIN_Y + TOP_PAD);
        final int startYRight = snap(MIN_Y + TOP_PAD);

        CircuitMutation mu = new CircuitMutation(newCirc);
        List<String> order = new ArrayList<>();

        int idxL = 0;

        // INPUTS (izquierda)
        for (String pname : ins) {
            int w = Math.max(1, widthFromEndpoints(cell, pname));
            int y = snap(yAtIndex(startYLeft, stepLeft, ++idxL));
            addPinToMutation(mu, Location.create(leftX, y),
                    /*isOutput*/ false, /*tri*/ false, w, pname,
                    Direction.EAST, Direction.EAST);
            order.add(pname);
        }
        // INOUTS (izquierda, tri-state activado)
        for (String pname : inouts) {
            int w = Math.max(1, widthFromEndpoints(cell, pname));
            int y = snap(yAtIndex(startYLeft, stepLeft, ++idxL));
            addPinToMutation(mu, Location.create(leftX, y),
                    /*isOutput*/ true, /*tri*/ true, w, pname,
                    Direction.EAST, Direction.EAST);
            order.add(pname);
        }

        // OUTPUTS (derecha)
        int idxR = 0;
        for (String pname : outs) {
            int w = Math.max(1, widthFromEndpoints(cell, pname));
            int y = snap(yAtIndex(startYRight, stepRight, ++idxR));
            addPinToMutation(mu, Location.create(rightX, y),
                    /*isOutput*/ true, /*tri*/ false, w, pname,
                    Direction.WEST, Direction.WEST);
            order.add(pname);
        }

        proj.doAction(mu.toAction(Strings.getter("addComponentAction", Pin.FACTORY.getDisplayGetter())));
        return order;
    }

    /**
     * Si no encontramos el módulo real, creamos pines “dummy”:
     * - orden alfabético por nombre
     * - primera mitad: INPUT (izquierda, EAST)
     * - segunda mitad: OUTPUT (derecha, WEST)
     * Devuelve la lista de nombres en el orden EXACTO en que fueron añadidos (para PortGeom).
     */
    private List<String> populateFallbackPins(Project proj, Circuit newCirc, VerilogCell cell) throws CircuitException {
        List<String> names = new ArrayList<>(cell.getPortNames());
        names.sort(String::compareTo);

        int n = names.size();
        int nIn  = n / 2; // primera mitad IN, segunda mitad OUT
        List<String> inNames  = new ArrayList<>(names.subList(0, nIn));
        List<String> outNames = new ArrayList<>(names.subList(nIn, n));

        // “Pista” vertical
        int rawSpan = TOP_PAD + (n * MIN_GAP) + BOT_PAD;
        final int spanY  = roundUpToGrid(Math.max(100, rawSpan));

        // Columnas
        final int leftX  = snap(MIN_X + 40);
        final int rightX = snap(MIN_X + 240);

        // Steps
        final int stepIn  = calcStep(spanY, Math.max(1, inNames.size()));
        final int stepOut = calcStep(spanY, Math.max(1, outNames.size()));

        // Y base
        final int startYIn  = snap(MIN_Y + TOP_PAD);
        final int startYOut = snap(MIN_Y + TOP_PAD);

        CircuitMutation mu = new CircuitMutation(newCirc);
        List<String> order = new ArrayList<>();

        // Inputs (izquierda)
        int kIn = 0;
        for (String pname : inNames) {
            int w = Math.max(1, safePortWidth(cell, pname));
            int y = snap(yAtIndex(startYIn, stepIn, ++kIn));
            addPinToMutation(mu, Location.create(leftX, y),
                    false, false, w, pname, Direction.EAST, Direction.EAST);
            order.add(pname);
        }

        // Outputs (derecha)
        int kOut = 0;
        for (String pname : outNames) {
            int w = Math.max(1, safePortWidth(cell, pname));
            int y = snap(yAtIndex(startYOut, stepOut, ++kOut));
            addPinToMutation(mu, Location.create(rightX, y),
                    true, false, w, pname, Direction.WEST, Direction.WEST);
            order.add(pname);
        }

        proj.doAction(mu.toAction(Strings.getter("addComponentAction", Pin.FACTORY.getDisplayGetter())));
        return order;
    }

    /** Añade un pin a un CircuitMutation sin ejecutar doAction aún. */
    private static void addPinToMutation(CircuitMutation mutation,
                                         Location loc,
                                         boolean isOutput,
                                         boolean triState,
                                         int width,
                                         String label,
                                         Direction facing,
                                         Direction labelLoc) {
        AttributeSet a = Pin.FACTORY.createAttributeSet();
        a.setValue(Pin.ATTR_TYPE, isOutput);
        a.setValue(Pin.ATTR_TRISTATE, triState);
        a.setValue(StdAttr.WIDTH, BitWidth.create(width));
        a.setValue(StdAttr.FACING, facing);
        a.setValue(StdAttr.LABEL, label);
        a.setValue(Pin.ATTR_LABEL_LOC, labelLoc);
        mutation.add(Pin.FACTORY.createComponent(loc, a));
    }

    /* -------------------- Helpers -------------------- */

    private static String safeName(String n) {
        return (n == null || n.isBlank()) ? "unnamed" : n;
    }

    private static int safePortWidth(VerilogCell cell, String pname) {
        try {
            int w = cell.portWidth(pname);
            return (w <= 0 ? 1 : w);
        } catch (Throwable t) {
            return 1;
        }
    }

    private static Circuit findCircuit(LogisimFile file, String name) {
        for (Circuit c : file.getCircuits()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }

    /** Verificador de componentes dentro de un circuito dado */
    public static boolean circuitHasAnyComponent(Circuit c) {
        try {
            var f = Circuit.class.getDeclaredField("components");
            f.setAccessible(true);
            Collection<?> comps = (Collection<?>) f.get(c);
            return comps != null && !comps.isEmpty();
        } catch (Throwable ignore) {
            try {
                var m = Circuit.class.getMethod("getAllComponents");
                Collection<?> comps = (Collection<?>) m.invoke(c);
                return comps != null && !comps.isEmpty();
            } catch (Throwable ex) {
                return false; // asumimos vacío si no podemos inspeccionar
            }
        }
    }

    private static Map<String, PortDirection> directionsFromEndpoints(VerilogCell cell) {
        Map<String, EnumSet<PortDirection>> acc = new LinkedHashMap<>();

        for (PortEndpoint ep : cell.endpoints()) {
            String p = ep.getPortName();
            PortDirection d = (ep.getDirection() != null) ? ep.getDirection() : PortDirection.UNKNOWN;
            acc.computeIfAbsent(p, __ -> EnumSet.noneOf(PortDirection.class)).add(d);
        }

        Map<String, PortDirection> out = new LinkedHashMap<>();
        for (var e : acc.entrySet()) {
            EnumSet<PortDirection> dirs = e.getValue();
            dirs.remove(PortDirection.UNKNOWN);
            PortDirection resolved;
            if (dirs.isEmpty()) {
                resolved = PortDirection.UNKNOWN;
            } else if (dirs.size() == 1) {
                resolved = dirs.iterator().next();
            } else {
                // Mezcla (poco probable) => trátalo como INOUT
                resolved = PortDirection.INOUT;
            }
            out.put(e.getKey(), resolved);
        }
        return out;
    }

    private static int widthFromEndpoints(VerilogCell cell, String port) {
        int maxIdx = -1;
        for (PortEndpoint ep : cell.endpoints()) {
            if (!port.equals(ep.getPortName())) continue;
            maxIdx = Math.max(maxIdx, ep.getBitIndex());
        }
        return (maxIdx >= 0) ? (maxIdx + 1) : Math.max(1, safePortWidth(cell, port));
    }
}
