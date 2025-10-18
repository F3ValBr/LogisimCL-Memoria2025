package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.*;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Constant;
import com.cburch.logisim.verilog.comp.auxiliary.LogicalMemory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.file.ui.NameConflictUI;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.layout.builder.LayoutBuilder;
import org.eclipse.elk.graph.ElkNode;
import com.cburch.logisim.verilog.std.Strings;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

public class ImporterUtils {

    static final class Geom {
        /** Creates a scratch Graphics context for measuring components. */
        static Graphics makeScratchGraphics() {
            return new BufferedImage(1,1, BufferedImage.TYPE_INT_ARGB).getGraphics();
        }

        /** Rounds v down to the nearest multiple of 10. */
        static int snap(int v){ return (v/10)*10; }

        /** Returns the location obtained by moving 'step' units from 'base' in 'facing' direction. */
        static Location stepFrom(Location base, Direction facing, int step) {
            int dx=0,dy=0;
            if (facing == Direction.EAST) dx = step;
            else if (facing == Direction.WEST) dx = -step;
            else if (facing == Direction.SOUTH) dy = step;
            else if (facing == Direction.NORTH) dy = -step;
            return Location.create(base.getX()+dx, base.getY()+dy);
        }

        /** Computes the bounding box of all cells in the given layout result. */
        static Bounds cellsBounds(LayoutBuilder.Result elk) {
            int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
            int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;
            for (var e : elk.cellNode.entrySet()) {
                ElkNode n = e.getValue(); if (n == null) continue;
                int x0=(int)Math.round(n.getX()), y0=(int)Math.round(n.getY());
                int x1=x0+(int)Math.round(n.getWidth()), y1=y0+(int)Math.round(n.getHeight());
                minX=Math.min(minX,x0); minY=Math.min(minY,y0);
                maxX=Math.max(maxX,x1); maxY=Math.max(maxY,y1);
            }
            if (minX==Integer.MAX_VALUE) return Bounds.create(100,100,100,100);
            return Bounds.create(minX,minY,Math.max(1,maxX-minX),Math.max(1,maxY-minY));
        }
    }

    static final class Attrs {

        /**
         * Sets the "value" attribute of a Constant component's AttributeSet,
         * trying to be flexible with respect to the actual type of the attribute.
         * <p>
         * The method tries several strategies:
         * <ol>
         *     <li>Mask the given value to fit in the given width (at least 1 bit).</li>
         *     <li>Locate the "value" attribute, either by Constant.ATTR_VALUE or by name search.</li>
         *     <li>Try to set the value as an Integer.</li>
         *     <li>If that fails, try to set it as a Value (if the build supports it).</li>
         *     <li>If that fails, try to parse it as a String using the attribute's parse method.</li>
         * </ol>
         * If all attempts fail, the method does nothing.
         */
        @SuppressWarnings({"rawtypes","unchecked"})
        static void setConstantValueFlexible(AttributeSet a, int width, int value) {
            // 1) Máscara al ancho
            final int w = Math.max(1, width);
            final int masked = (w >= 32) ? value : (value & ((1 << w) - 1));

            // 2) Localiza el atributo de valor
            Attribute valueAttr = null;
            try { valueAttr = Constant.ATTR_VALUE; } catch (Throwable ignore) { }

            if (valueAttr == null) {
                // Búsqueda por nombre, tolerante a cambios de clase
                for (Attribute<?> at : a.getAttributes()) {
                    String nm = String.valueOf(at.getName());
                    if (nm != null && nm.toLowerCase().contains("value")) {
                        valueAttr = at;
                        break;
                    }
                }
            }
            if (valueAttr == null) return; // no hay atributo de valor

            // Helper local para setear evitando problemas de genéricos
            Attribute finalValueAttr = valueAttr;
            java.util.function.Consumer<Object> set = (obj) -> a.setValue(finalValueAttr, obj);

            // 3) Si el atributo actualmente contiene un Integer, usa Integer
            try {
                Object cur = a.getValue(valueAttr);
                if (cur instanceof Integer || cur == null) {
                    set.accept(masked);
                    return;
                }
            } catch (Throwable ignore) { /* sigue */ }

            // 4) Intento A: Integer (aunque cur fuese de otro tipo, puede que igual lo acepte)
            try {
                set.accept(masked);
                return;
            } catch (Throwable ignore) { /* sigue */ }

            // 5) Intento B: usar el parse(String) del atributo (respeta el tipo concreto del build)
            try {
                String hex = "0x" + Integer.toHexString(masked);
                Object parsed = valueAttr.parse(hex);
                set.accept(parsed);
                return;
            } catch (Throwable ignore) { /* sigue */ }

            // 6) Intento C: Value (solo si lo acepta este build)
            try {
                Value val = Value.createKnown(BitWidth.create(w), masked);
                set.accept(val);
            } catch (Throwable ignore) {
                // Ya no hay más que podamos hacer. Silencioso a propósito.
            }
        }
    }


    static final class Components {
        /**
         * Adds a component to the circuit at the given location, after checking for conflicts.
         * If the component would be placed out of bounds (negative coordinates), it is shifted
         * into the positive quadrant. If it still cannot fit, a CircuitException is thrown.
         * <p>
         * The addition is performed as a CircuitMutation and registered in the project's undo history.
         *
         * @param proj    Project where to add the component (for undo support)
         * @param circ    Circuit where to add the component
         * @param g       Graphics context for measuring component bounds
         * @param factory Factory to create the component
         * @param where   Location where to place the component
         * @param attrs   AttributeSet for the new component
         * @return The newly added Component
         * @throws CircuitException if there is a conflict or if the component cannot be placed
         */
        static Component addComponentSafe(Project proj,
                                          Circuit circ,
                                          Graphics g,
                                          ComponentFactory factory,
                                          Location where,
                                          AttributeSet attrs) throws CircuitException {
            Component comp = factory.createComponent(where, attrs);
            if (circ.hasConflict(comp)) throw new CircuitException(Strings.get("exclusiveError"));

            Bounds b = comp.getBounds(g);
            int shiftX=0, shiftY=0;
            if (b.getX() < VerilogJsonImporter.MIN_X) shiftX = VerilogJsonImporter.MIN_X - b.getX();
            if (b.getY() < VerilogJsonImporter.MIN_Y) shiftY = VerilogJsonImporter.MIN_Y - b.getY();
            if (shiftX!=0 || shiftY!=0) {
                where = Location.create(where.getX()+Geom.snap(shiftX), where.getY()+Geom.snap(shiftY));
                comp = factory.createComponent(where, attrs);
                b = comp.getBounds(g);
            }
            if (b.getX()<0 || b.getY()<0) throw new CircuitException(Strings.get("negativeCoordError"));

            CircuitMutation m = new CircuitMutation(circ);
            m.add(comp);
            proj.doAction(m.toAction(Strings.getter("addComponentAction", factory.getDisplayGetter())));
            return comp;
        }

        static Circuit ensureCircuit(Project proj, String name) {
            LogisimFile file = proj.getLogisimFile();

            // ¿ya existe?
            Circuit existing = findCircuit(file, name);
            if (existing == null) {
                Circuit c = new Circuit(name);
                proj.doAction(LogisimFileActions.addCircuit(c));
                return c;
            }

            // Delegar decisión a la capa UI
            NameConflictUI.NameConflictResult res = NameConflictUI.askUser(proj, name);
            switch (res.choice()) {
                case REPLACE -> {
                    proj.doAction(LogisimFileActions.removeCircuit(existing));
                    Circuit c = new Circuit(name);
                    proj.doAction(LogisimFileActions.addCircuit(c));
                    return c;
                }
                case CREATE_NEW -> {
                    String newName = makeUniqueName(file, res.suggestedName() != null ? res.suggestedName() : (name + "_new"));
                    Circuit c = new Circuit(newName);
                    proj.doAction(LogisimFileActions.addCircuit(c));
                    return c;
                }
                case CANCEL -> {
                    // Devolvemos el existente para no romper flujos
                    return existing;
                }
                default -> {
                    // Fallback defensivo
                    return existing;
                }
            }
        }

        // ===== Helpers sin UI =====
        private static Circuit findCircuit(LogisimFile file, String name) {
            for (Circuit c : file.getCircuits()) {
                if (c.getName().equals(name)) return c;
            }
            return null;
        }

        private static boolean circuitExists(LogisimFile file, String name) {
            return findCircuit(file, name) != null;
        }

        private static String makeUniqueName(LogisimFile file, String base) {
            String candidate = base;
            int i = 1;
            while (circuitExists(file, candidate)) {
                candidate = base + i;
                i++;
            }
            return candidate;
        }
    }

    static final class MemoryAlias {
        static Map<VerilogCell, VerilogCell> build(VerilogModuleImpl mod, MemoryIndex memIndex) {
            Map<VerilogCell, VerilogCell> alias = new HashMap<>();
            for (LogicalMemory lm : memIndex.memories()) {
                int arrIdx = lm.arrayCellIdx();
                VerilogCell rep = null;
                if (arrIdx >= 0) rep = mod.cells().get(arrIdx);
                else {
                    Integer idx = !lm.readPortIdxs().isEmpty() ? lm.readPortIdxs().get(0)
                            : (!lm.writePortIdxs().isEmpty() ? lm.writePortIdxs().get(0) : null);
                    if (idx != null) rep = mod.cells().get(idx);
                }
                if (rep == null) continue;
                for (Integer i : lm.readPortIdxs())  { var c = mod.cells().get(i); if (c!=rep) alias.put(c, rep); }
                for (Integer i : lm.writePortIdxs()) { var c = mod.cells().get(i); if (c!=rep) alias.put(c, rep); }
                for (Integer i : lm.initIdxs())      { var c = mod.cells().get(i); if (c!=rep) alias.put(c, rep); }
            }
            return alias;
        }
    }

    /* ========================================
       Helpers de impresión (usar en debugging)
       ======================================== */

    /*
    static void printModulePorts(VerilogModuleImpl mod) {
        if (mod.ports().isEmpty()) {
            System.out.println("  (sin puertos de módulo)");
            return;
        }
        System.out.println("  Puertos:");
        for (ModulePort p : mod.ports()) {
            String bits = Arrays.stream(p.netIds())
                    .mapToObj(i -> i == ModulePort.CONST_0 ? "0" :
                            i == ModulePort.CONST_1 ? "1" :
                                    i == ModulePort.CONST_X ? "x" : String.valueOf(i))
                    .collect(Collectors.joining(","));
            System.out.println("    - " + p.name() + " : " + p.direction()
                    + " [" + p.width() + "]  bits={" + bits + "}");
        }
    }

    static void printNets(VerilogModuleImpl mod, ModuleNetIndex idx) {
        System.out.println("  Nets:");
        for (int netId : idx.netIds()) {
            int[] refs = idx.endpointsOf(netId).stream().mapToInt(i -> i).toArray();

            var topStrs  = new ArrayList<String>();
            var cellStrs = new ArrayList<String>();

            for (int ref : refs) {
                int bit = ModuleNetIndex.bitIdx(ref);
                if (ModuleNetIndex.isTop(ref)) {
                    int portIdx = ModuleNetIndex.ownerIdx(ref);
                    ModulePort p = mod.ports().get(portIdx);
                    topStrs.add(p.name() + "[" + bit + "]");
                } else {
                    int cellIdx = ModuleNetIndex.ownerIdx(ref);
                    VerilogCell c = mod.cells().get(cellIdx);
                    cellStrs.add(c.name() + "[" + bit + "]");
                }
            }

            StringBuilder sb = new StringBuilder();
            sb.append("    net ").append(netId).append(": ");
            if (!topStrs.isEmpty())  sb.append("top=").append(topStrs).append(" ");
            if (!cellStrs.isEmpty()) sb.append("cells=").append(cellStrs);
            System.out.println(sb);
        }
    }

    static void printMemories(MemoryIndex memIndex) {
        var all = memIndex.memories();
        if (all == null || all.isEmpty()) return;

        System.out.println("  Memories:");
        for (LogicalMemory lm : all) {
            String meta = (lm.meta() == null)
                    ? ""
                    : (" width=" + lm.meta().width()
                    + " size=" + lm.meta().size()
                    + " offset=" + lm.meta().startOffset());

            System.out.println("    - MEMID=" + lm.memId()
                    + " arrayCellIdx=" + (lm.arrayCellIdx() < 0 ? "-" : lm.arrayCellIdx())
                    + " rdPorts=" + lm.readPortIdxs().size()
                    + " wrPorts=" + lm.writePortIdxs().size()
                    + " inits=" + lm.initIdxs().size()
                    + meta);
        }
    }
     */
}
