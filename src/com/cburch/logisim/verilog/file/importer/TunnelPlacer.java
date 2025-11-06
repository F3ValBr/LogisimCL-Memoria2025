package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.BitLabeledTunnel;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.awt.Graphics;
import java.util.*;

import static com.cburch.logisim.verilog.file.importer.ImporterUtils.Geom.snap;
import static com.cburch.logisim.verilog.file.importer.ImporterUtils.NetnameUtils.resolveNetname;

final class TunnelPlacer {
    private final int grid;

    // radio mínimo para decir “esto se solapa visualmente”
    private static final int MIN_SPACING = 30;

    // mapeo de desplazamientos por celda
    private final Map<Object, Map<Direction, List<Location>>> usedPerOwner = new IdentityHashMap<>();

    /** Creates a new tunnel placer.
     * @param grid Grid size to use for placement.
     */
    TunnelPlacer(int grid) {
        this.grid = grid;
    }

    /**
     * Places tunnels for all non-constant signals.
     * @param batch Import batch where to add new components.
     * @param mod Module being imported.
     * @param cellHandles Map from cells to their instance handles.
     * @param topAnchors Map from top module ports to their anchors.
     * @param g Graphics context for measuring components.
     * @param specs Specification builder to use for analyzing specifications.
     */
    void place(ImportBatch batch,
               VerilogModuleImpl mod,
               Map<VerilogCell, InstanceHandle> cellHandles,
               Map<ModulePort, LayoutServices.PortAnchor> topAnchors,
               Graphics g,
               SpecBuilder specs) {

        // clave de deduplicación
        record K(int x, int y, String csvKey, int w, Direction facing, boolean out) {}
        Set<K> placed = new HashSet<>();

        /* ===== TOP ports ===== */
        for (ModulePort p : mod.ports()) {
            var anc = topAnchors.get(p);
            if (anc == null) continue;

            List<String> bitSpecs = specs.buildBitSpecsForTopPort(mod.name(), p);
            boolean all01 = bitSpecs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (all01) continue; // constantes puras: las maneja ConstantPlacer

            boolean attrOutput = (p.direction() == PortDirection.OUTPUT);

            // túnel mira hacia afuera
            Direction facing = (anc.facing() == Direction.EAST) ? Direction.WEST : Direction.EAST;

            String pretty = SpecBuilder.makePrettyLabel(bitSpecs);
            String label  = resolveNetname(mod, bitSpecs).orElse(pretty);

            int w = Math.max(1, p.width());

            // punto base (una celda afuera)
            Location base = ImporterUtils.Geom.stepFrom(anc.loc(), facing, grid);

            Location finalLoc = avoidOverlap(p, base, facing);

            String csvKey = String.join(",", bitSpecs);
            K key = new K(finalLoc.getX(), finalLoc.getY(), csvKey, w, facing, attrOutput);
            if (!placed.add(key)) continue;

            if (w <= 32) {
                createBitLabeledTunnel(batch, anc.loc(), w, bitSpecs, label, facing, attrOutput, finalLoc);
            } else {
                placeOverflowTunnels(batch, anc.loc(), w, bitSpecs, facing, attrOutput, finalLoc);
            }
        }

        /* ===== Celdas ===== */
        for (var e : cellHandles.entrySet()) {
            VerilogCell cell = e.getKey();
            InstanceHandle ih = e.getValue();
            if (ih == null || ih.ports == null) continue;

            for (String port : cell.getPortNames()) {
                int w = Math.max(1, cell.portWidth(port));

                // saltar puertos constantes
                PortEndpoint[] byIdx = new PortEndpoint[w];
                for (PortEndpoint ep : cell.endpoints()) {
                    if (!port.equals(ep.getPortName())) continue;
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < w) byIdx[i] = ep;
                }
                SpecBuilder.ConstAnalysis ca = SpecBuilder.analyzeConstBits(byIdx);
                if (ca.allPresent() && ca.all01()) continue;

                Location pin = ih.ports.locateByName(port);
                if (pin == null) continue;

                List<String> bitSpecs = specs.buildBitSpecsForCellPort(mod.name(), cell, port);
                String pretty = SpecBuilder.makePrettyLabel(bitSpecs);
                String label  = resolveNetname(mod, bitSpecs).orElse(pretty);

                Direction facing = LayoutServices.facingByNearestBorder(
                        LayoutServices.figureBounds(ih.component, g), pin);
                boolean attrOutput = SpecBuilder.isInput(cell, port, facing);

                Location base = ImporterUtils.Geom.stepFrom(pin, facing, grid);

                Location finalLoc = avoidOverlap(cell, base, facing);

                String csvKey = String.join(",", bitSpecs);
                K key = new K(finalLoc.getX(), finalLoc.getY(), csvKey, w, facing, attrOutput);
                if (!placed.add(key)) continue;

                if (w <= 32) {
                    createBitLabeledTunnel(batch, pin, w, bitSpecs, label, facing, attrOutput, finalLoc);
                } else {
                    placeOverflowTunnels(batch, pin, w, bitSpecs, facing, attrOutput, finalLoc);
                }
            }
        }
    }

    /**
     * Devuelve una posición cercana libre de solape visual PARA ESE OWNER Y ESA ORIENTACIÓN.
     * - si mira EAST/WEST → apilamos en Y y vamos empujando en X hacia afuera según cuántos ya haya
     * - si mira NORTH/SOUTH → apilamos en X y empujamos en Y
     */
    private Location avoidOverlap(Object owner, Location base, Direction facing) {
        // bucket por dueño
        Map<Direction, List<Location>> byFacing =
                usedPerOwner.computeIfAbsent(owner, o -> new HashMap<>());

        // bucket por orientación dentro del dueño
        List<Location> list =
                byFacing.computeIfAbsent(facing, f -> new ArrayList<>());

        // cuántos ya tiene este dueño en ESTA orientación
        int countSameFacing = list.size();

        // desplazamiento lateral básico: múltiplos del grid
        int lateralStep = Math.max(10, grid / 2);
        int lateralOffset = countSameFacing * lateralStep;

        // primer candidato: base + desplazamiento lateral según facing
        Location first;
        if (facing == Direction.EAST) {
            // túnel está a la izquierda del comp → empuja más a la izquierda
            first = Location.create(snap(base.getX() - lateralOffset), base.getY());
        } else if (facing == Direction.WEST) {
            // túnel está a la derecha del comp → empuja más a la derecha
            first = Location.create(snap(base.getX() + lateralOffset), base.getY());
        } else if (facing == Direction.NORTH) {
            // túnel abajo del comp → empuja más abajo
            first = Location.create(base.getX(), snap(base.getY() + lateralOffset));
        } else { // SOUTH
            // túnel arriba del comp → empuja más arriba
            first = Location.create(base.getX(), snap(base.getY() - lateralOffset));
        }

        // si ese primer candidato no choca con los ya puestos para ESTE dueño+facing, lo usamos
        if (!isTooCloseToAny(first, list)) {
            list.add(first);
            return first;
        }

        // si choca, probamos algunos desplazamientos verticales/horizontales alrededor
        int step = grid;
        int tries = 8;
        int dir = 1;
        for (int i = 1; i <= tries; i++) {
            Location cand;
            if (facing == Direction.EAST || facing == Direction.WEST) {
                // mover en Y alrededor
                cand = Location.create(first.getX(), first.getY() + dir * i * step);
            } else {
                // mover en X alrededor
                cand = Location.create(first.getX() + dir * i * step, first.getY());
            }
            if (!isTooCloseToAny(cand, list)) {
                list.add(cand);
                return cand;
            }
            dir = -dir;
        }

        // última opción: usar el base sin offset
        list.add(base);
        return base;
    }

    private boolean isTooCloseToAny(Location p, List<Location> list) {
        for (Location q : list) {
            int dx = Math.abs(p.getX() - q.getX());
            int dy = Math.abs(p.getY() - q.getY());
            if (dx < MIN_SPACING && dy < MIN_SPACING) {
                return true;
            }
        }
        return false;
    }

    /* ====== creación de túneles ====== */

    /** Creates and places a bit-labeled tunnel with a wire connecting to the mouth location.
     * @param batch Import batch where to add new components.
     * @param mouth Location where the tunnel should connect.
     * @param width Width of the tunnel in bits.
     * @param bitSpecs List of bit specifications for the tunnel.
     * @param label Optional label for the tunnel (can be null or blank).
     * @param facing Direction the tunnel should face.
     * @param attrOutput Whether to set the ATTR_OUTPUT attribute (only if facing is not WEST).
     * @param tunnelPinLoc Updated location for the tunnel where is going to be placed.
     */
    private void createBitLabeledTunnel(ImportBatch batch,
                                        Location mouth,
                                        int width,
                                        List<String> bitSpecs,
                                        String label,
                                        Direction facing,
                                        boolean attrOutput,
                                        Location tunnelPinLoc) {
        try {
            // 1) cables
            int x1 = tunnelPinLoc.getX();
            int y1 = tunnelPinLoc.getY();
            int x2 = mouth.getX();
            int y2 = mouth.getY();

            Location mid;
            if (facing == Direction.EAST || facing == Direction.WEST) {
                mid = Location.create(x1, y2);
            } else {
                mid = Location.create(x2, y1);
            }
            batch.add(Wire.create(tunnelPinLoc, mid));
            batch.add(Wire.create(mid, mouth));

            // 2) crear el BLT
            BitLabeledTunnel bltFactory = BitLabeledTunnel.FACTORY;
            AttributeSet a = bltFactory.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
            a.setValue(BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
            a.setValue(BitLabeledTunnel.ATTR_OUTPUT, attrOutput);
            a.setValue(StdAttr.FACING, facing);
            if (label != null && !label.isBlank()) {
                a.setValue(StdAttr.LABEL, label);
            }

            // compensar offset
            Component probe = bltFactory.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location tunLoc = Location.create(
                    tunnelPinLoc.getX() - offX,
                    tunnelPinLoc.getY() - offY
            );

            batch.add(bltFactory.createComponent(tunLoc, a));
        } catch (Exception ignored) { }
    }

    /* ===================== Overflow helpers ===================== */

    private void placeOverflowTunnels(ImportBatch batch,
                                      Location mouth,
                                      int width,
                                      List<String> specs,
                                      Direction facing,
                                      boolean attrOutput,
                                      Location tunnelPinLoc) {
        final int LOW = 32;
        final int wEff = Math.max(1, width);
        final int lowCount = Math.min(LOW, wEff);
        final int rest = Math.max(0, wEff - lowCount);

        List<String> safe = new ArrayList<>(wEff);
        for (int i = 0; i < wEff; i++) {
            safe.add(i < specs.size() ? nonNullTrim(specs.get(i)) : "x");
        }

        // bloque principal
        List<String> lowSpecs = safe.subList(0, lowCount);
        String lblLow = SpecBuilder.makePrettyLabel(lowSpecs);
        createBitLabeledTunnel(batch, mouth, lowCount, lowSpecs, lblLow, facing, attrOutput, tunnelPinLoc);

        if (rest > 0) {
            // lo tiramos más atrás
            Location side = ImporterUtils.Geom.stepFrom(tunnelPinLoc, facing, -3 * grid);
            List<String> restSpecs = new ArrayList<>(rest);
            for (int i = 0; i < rest; i++) {
                restSpecs.add(safe.get(lowCount + i));
            }
            String lblRest = SpecBuilder.makePrettyLabel(restSpecs);
            createBltDecorated(batch, side, rest, restSpecs, lblRest, facing, attrOutput, ">32 bits");
        }
    }

    private void createBltDecorated(ImportBatch batch,
                                    Location where,
                                    int width,
                                    List<String> bitSpecs,
                                    String label,
                                    Direction facing,
                                    boolean attrOutput,
                                    String suffixNote) {
        try {
            BitLabeledTunnel bltFactory = BitLabeledTunnel.FACTORY;
            AttributeSet a = bltFactory.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
            a.setValue(BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
            a.setValue(BitLabeledTunnel.ATTR_OUTPUT, attrOutput);
            a.setValue(StdAttr.FACING, facing);

            String lbl = (label == null ? "" : label.trim());
            if (!lbl.isEmpty() && suffixNote != null && !suffixNote.isBlank()) {
                lbl = lbl + " " + suffixNote;
            } else if (lbl.isEmpty() && suffixNote != null && !suffixNote.isBlank()) {
                lbl = suffixNote;
            }
            if (!lbl.isBlank()) a.setValue(StdAttr.LABEL, lbl);

            Component probe = bltFactory.createComponent(Location.create(0, 0), a);
            EndData end0 = probe.getEnd(0);
            int offX = end0.getLocation().getX() - probe.getLocation().getX();
            int offY = end0.getLocation().getY() - probe.getLocation().getY();
            Location tunLoc = Location.create(where.getX() - offX, where.getY() - offY);

            batch.add(bltFactory.createComponent(tunLoc, a));
        } catch (Exception ignored) { }
    }

    private static String nonNullTrim(String s) {
        return (s == null) ? "x" : s.trim();
    }
}
