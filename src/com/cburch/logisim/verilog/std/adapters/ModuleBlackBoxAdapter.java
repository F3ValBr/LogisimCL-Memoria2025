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
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.std.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

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
                orderForNameToIdx = populateFallbackPins(proj, newCirc, cell);
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

    /* -------------------- Fallback: mitad IN / mitad OUT -------------------- */

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
        int nIn  = n / 2;           // mitad inferior

        List<String> inNames  = new ArrayList<>(names.subList(0, nIn));
        List<String> outNames = new ArrayList<>(names.subList(nIn, n));

        // Layout básico
        final int spanY = Math.max(100, (n + 1) * GRID);
        final int leftX = MIN_X + 40, rightX = MIN_X + 240;
        final int inStep  = Math.max(GRID, spanY / Math.max(1, inNames.size()  + 1));
        final int outStep = Math.max(GRID, spanY / Math.max(1, outNames.size() + 1));
        int curInY = MIN_Y + inStep, curOutY = MIN_Y + outStep;

        CircuitMutation mu = new CircuitMutation(newCirc);
        List<String> order = new ArrayList<>();

        // Inputs (izquierda)
        for (String pname : inNames) {
            int w = Math.max(1, safePortWidth(cell, pname));
            addPinToMutation(mu, Location.create(leftX, curInY), false, false, w, pname, Direction.EAST, Direction.EAST);
            order.add(pname);
            curInY += inStep;
        }

        // Outputs (derecha)
        for (String pname : outNames) {
            int w = Math.max(1, safePortWidth(cell, pname));
            addPinToMutation(mu, Location.create(rightX, curOutY), true, false, w, pname, Direction.WEST, Direction.WEST);
            order.add(pname);
            curOutY += outStep;
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
}
