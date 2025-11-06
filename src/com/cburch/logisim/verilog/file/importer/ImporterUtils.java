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
import com.cburch.logisim.verilog.comp.auxiliary.NetnameEntry;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.file.ui.NameConflictUI;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.layout.builder.LayoutBuilder;
import org.eclipse.elk.graph.ElkNode;
import com.cburch.logisim.verilog.std.Strings;


import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.*;
import java.util.List;

import static com.cburch.logisim.verilog.file.importer.VerilogJsonImporter.GRID;

public class ImporterUtils {

    public static final class Geom {
        /** Creates a scratch Graphics context for measuring components. */
        static Graphics makeScratchGraphics() {
            return new BufferedImage(1,1, BufferedImage.TYPE_INT_ARGB).getGraphics();
        }

        /** Rounds v down to the nearest multiple of GRID. */
        public static int snap(int v){ return (v/GRID)*GRID; }

        /** Returns the location obtained by moving 'step' units from 'base' in 'facing' direction. */
        static Location stepFrom(Location base, Direction facing, int step) {
            int dx=0,dy=0;
            if (facing == Direction.EAST) dx = -step;
            else if (facing == Direction.WEST) dx = step;
            else if (facing == Direction.SOUTH) dy = -step;
            else if (facing == Direction.NORTH) dy = step;
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

    static final class NetnameUtils {
        static Optional<String> resolveNetname(VerilogModuleImpl mod, List<String> bitSpecs) {
            if (bitSpecs == null || bitSpecs.isEmpty()) return Optional.empty();

            final int n = bitSpecs.size();
            final Integer[] ids = new Integer[n];
            final String[] raw = new String[n];
            boolean hasAnyNet = false;
            boolean hasAnyConst = false;

            // 1) Parseo estricto (0/1/x o N<num>=0). Tokens libres -> aborta
            for (int i = 0; i < n; i++) {
                String s = bitSpecs.get(i);
                if (s == null) return Optional.empty();
                s = s.trim();
                raw[i] = s;

                if ("0".equals(s) || "1".equals(s) || "x".equalsIgnoreCase(s)) {
                    ids[i] = null;
                    hasAnyConst = true;
                    continue;
                }
                if (s.length() >= 2 && (s.charAt(0) == 'N' || s.charAt(0) == 'n')) {
                    try {
                        int id = Integer.parseInt(s.substring(1).trim());
                        if (id < 0) { // no aceptamos nets negativas
                            ids[i] = null;
                            hasAnyConst = true;
                        } else {
                            ids[i] = id;
                            hasAnyNet = true;
                        }
                        continue;
                    } catch (NumberFormatException ignore) {
                        return Optional.empty();
                    }
                }
                // Token libre (nombre textual de net) => no intentamos renombrar
                return Optional.empty();
            }
            if (!hasAnyNet) return Optional.empty();

            // 2) Caso rápido: match exacto (sin constantes)
            if (!hasAnyConst) {
                int[] flat = Arrays.stream(ids).mapToInt(Integer::intValue).toArray();
                String exact = findExactNetname(mod, flat);
                if (exact != null) return Optional.of(exact);
            }

            // 3) Candidatos: para cada netname que contenga TODOS los ids en el MISMO orden (subsecuencia)
            List<Integer> seq = new ArrayList<>();
            for (Integer v : ids) if (v != null) seq.add(v);

            String best = null;
            for (NetnameEntry nn : mod.netnames()) {
                if (nn.hideName()) continue;
                int[] nb = nn.bits();
                if (nb == null || nb.length == 0) continue;

                // subsecuencia en orden
                if (!isSubsequenceInOrder(nb, seq)) continue;

                // 3.a) Si es subsecuencia, construye piezas
                List<String> pieces = new ArrayList<>();
                int i = 0;
                while (i < n) {
                    // bloque de constantes
                    if (ids[i] == null) {
                        pieces.add(raw[i].toLowerCase());
                        i++;
                        continue;
                    }
                    // bloque de nets (intenta agrupar los que caen en este netname en runs por valor consecutivo)
                    int j = i;
                    List<Integer> bucket = new ArrayList<>();
                    while (j < n && ids[j] != null) {
                        bucket.add(ids[j]);
                        j++;
                    }
                    // bucket = N{ids} consecutivos en el CSV
                    pieces.addAll(splitBucketByPresenceAndCompact(nn, bucket));
                    i = j;
                }

                // 3.b) Compacta runs de constantes para legibilidad
                pieces = mergeConstantRuns(pieces, /*hideSingles=*/false);

                String candidate = String.join(";", pieces);
                // Preferimos la etiqueta más corta
                if (best == null || candidate.length() < best.length()) best = candidate;
            }

            if (best != null && !best.isBlank()) return Optional.of(best);

            // 4) Fallback: sin netname aplicable
            String pretty = SpecBuilder.makePrettyLabel(bitSpecs);
            if (!pretty.isBlank()) return Optional.of(pretty);

            return Optional.empty();
        }

        /* === Helpers ================================================================= */

        private static boolean isSubsequenceInOrder(int[] universe, List<Integer> seq) {
            if (seq.isEmpty()) return false;
            int p = 0;
            for (int v : seq) {
                p = indexOf(universe, v, p);
                if (p < 0) return false;
                p++;
            }
            return true;
        }

        private static int indexOf(int[] arr, int v, int from) {
            for (int k = Math.max(0, from); k < arr.length; k++)
                if (arr[k] == v) return k;
            return -1;
        }

        /**
         * Divide un bucket (lista contigua del CSV) en piezas:
         * - subruns que están dentro del netname 'nn' -> "nn.name():a-b" (compactado por valor)
         * - subruns que NO están dentro de 'nn' -> "Nstart-Nend"
         */
        private static List<String> splitBucketByPresenceAndCompact(NetnameEntry nn, List<Integer> bucket) {
            List<String> out = new ArrayList<>();
            if (bucket.isEmpty()) return out;
            int[] nb = nn.bits();
            // set para pertenencia O(1)
            java.util.BitSet in = new java.util.BitSet();
            int min = Integer.MAX_VALUE, max = Integer.MIN_VALUE;
            for (int id : nb) {
                in.set(id);
                if (id < min) min = id;
                if (id > max) max = id;
            }

            int i = 0;
            while (i < bucket.size()) {
                int id0 = bucket.get(i);
                boolean inside = (id0 >= min && id0 <= max && in.get(id0));

                // avanza tramo manteniendo "inside" y adyacencia por valor (prev+1)
                int j = i, prev = id0;
                while (j + 1 < bucket.size()) {
                    int nxt = bucket.get(j + 1);
                    boolean inNext = (nxt >= min && nxt <= max && in.get(nxt));
                    if (inNext != inside) break;
                    if (nxt != prev + 1) break; // solo compactamos adyacentes por valor
                    prev = nxt;
                    j++;
                }

                if (inside) {
                    // tramo perteneciente al netname
                    if (id0 == prev) out.add(nn.name() + ":" + id0);
                    else out.add(nn.name() + ":" + id0 + "-" + prev);
                } else {
                    // tramo fuera -> Nstart[-end]
                    if (id0 == prev) out.add("N" + id0);
                    else out.add("N" + id0 + "-" + prev);
                }
                i = j + 1;
            }
            return out;
        }

        /**
         * Match exacto: mismo largo y mismos IDs en el mismo orden.
         */
        static String findExactNetname(VerilogModuleImpl mod, int[] ids) {
            if (ids == null || ids.length == 0) return null;
            for (NetnameEntry nn : mod.netnames()) {
                if (nn.hideName()) continue;
                int[] nb = nn.bits();
                if (nb == null || nb.length != ids.length) continue;
                boolean eq = true;
                for (int i = 0; i < nb.length; i++) {
                    if (nb[i] != ids[i]) {
                        eq = false;
                        break;
                    }
                }
                if (eq) return nn.name();
            }
            return null;
        }

        /**
         * Compacta runs consecutivos de constantes iguales ("0","1","x") en piezas del tipo:
         * - una sola => "0"
         * - k seguidas => "k'b0" / "k'b1" / "k'bx"
         * No toca piezas con ":" (name:...) ni las "N..." ya compactadas.
         */
        private static List<String> mergeConstantRuns(List<String> pieces, boolean hideSingles) {
            if (pieces == null || pieces.isEmpty()) return pieces;

            List<String> out = new ArrayList<>(pieces.size());
            int i = 0;
            while (i < pieces.size()) {
                String p = pieces.get(i);
                if (isConst(p)) {
                    String tok = p.toLowerCase();
                    int j = i + 1;
                    while (j < pieces.size() && tok.equalsIgnoreCase(pieces.get(j))) j++;
                    int count = j - i;
                    if (!(hideSingles && count == 1)) {
                        out.add(count + "'b" + tok);
                    }
                    i = j;
                } else {
                    out.add(p);
                    i++;
                }
            }
            return out;
        }

        private static boolean isConst(String s) {
            if (s == null) return false;
            String t = s.trim().toLowerCase();
            return "0".equals(t) || "1".equals(t) || "x".equals(t);
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
