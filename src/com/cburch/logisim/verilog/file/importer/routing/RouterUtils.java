package com.cburch.logisim.verilog.file.importer.routing;

import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.verilog.file.importer.BitLabeledTunnelRewriter;

import java.awt.*;
import java.util.*;
import java.util.List;

public final class RouterUtils {

    /** Simplifies a polyline path by removing unnecessary points, trying to make longer HV/VH shortcuts while avoiding obstacles. */
    public static List<Location> simplifyPolyline(List<Location> poly, List<Bounds> obstacles, int clearHard) {
        if (poly == null || poly.size() < 3) return poly;
        List<Location> pts = new ArrayList<>(poly);
        boolean changed;
        int guard = 0;
        do {
            changed = false;
            // 1) intenta colapsar con HV/VH más largos (shortcut)
            outer:
            for (int i = 0; i + 2 < pts.size(); i++) {
                for (int j = i + 2; j < pts.size(); j++) {
                    Location a = pts.get(i);
                    Location b = pts.get(j);

                    // Opción A: directo (si ya están alineados)
                    if ((a.getX() == b.getX() || a.getY() == b.getY())
                            && pathClear(a, b, obstacles, clearHard)) {
                        // mantiene extremos, elimina lo de en medio
                        if (j > i + 1) {
                            pts.subList(i + 1, j).clear();
                        }
                        changed = true;
                        break outer;
                    }

                    // Opción B: HV
                    Location midH = Location.create(b.getX(), a.getY());
                    if (pathClear(a, midH, obstacles, clearHard) && pathClear(midH, b, obstacles, clearHard)) {
                        // sustituye tramo i..j por a-midH-b
                        List<Location> repl = List.of(a, midH, b);
                        // borra interior
                        if (j > i + 1) {
                            pts.subList(i + 1, j).clear();
                        }
                        // inserta midH si no estaba
                        if (!pts.get(i+1).equals(midH)) pts.add(i+1, midH);
                        changed = true;
                        break outer;
                    }

                    // Opción C: VH
                    Location midV = Location.create(a.getX(), b.getY());
                    if (pathClear(a, midV, obstacles, clearHard) && pathClear(midV, b, obstacles, clearHard)) {
                        if (j > i + 1) {
                            pts.subList(i + 1, j).clear();
                        }
                        if (!pts.get(i+1).equals(midV)) pts.add(i+1, midV);
                        changed = true;
                        break outer;
                    }
                }
            }

            // 2) compactar colineales (por si quedaron consecutivos alineados)
            if (compressOnce(pts)) changed = true;

            guard++;
        } while (changed && guard < 20);
        return pts;
    }

    /** Single pass to remove colinear points in a polyline. */
    public static boolean compressOnce(List<Location> pts) {
        if (pts.size() <= 2) return false;
        boolean changed = false;
        for (int i = 1; i + 1 < pts.size(); ) {
            Location a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
            boolean col = (a.getX() == b.getX() && b.getX() == c.getX())
                    || (a.getY() == b.getY() && b.getY() == c.getY());
            if (col) { pts.remove(i); changed = true; }
            else i++;
        }
        return changed;
    }

    /** Reunites Bounds of all components except those in the group (to avoid collisions). */
    public static List<Bounds> collectComponentBounds(Circuit circ, Graphics g, List<BitLabeledTunnelRewriter.TunnelInfo> ignoreGrp) {
        Set<Component> ignore = new HashSet<>();
        if (ignoreGrp != null) for (BitLabeledTunnelRewriter.TunnelInfo ti : ignoreGrp) ignore.add(ti.comp());

        List<Bounds> obs = new ArrayList<>();
        for (Component c : circ.getNonWires()) {
            if (ignore.contains(c)) continue;
            Bounds b = c.getBounds(g);
            if (b != null && b.getWidth() > 0 && b.getHeight() > 0) {
                // pequeño “inflate” para no besar bordes
                obs.add(b.expand(2));
            }
        }
        return obs;
    }

    /** Converts all existing wires of the circuit into obstacle-rectangles with margin. */
    public static List<Bounds> collectWireBounds(Circuit circ, int margin) {
        List<Bounds> out = new ArrayList<>();
        for (Wire w : circ.getWires()) {
            Location a = w.getEnd0();
            Location b = w.getEnd1();
            out.addAll(segmentAsWireBounds(a, b, margin));
        }
        return out;
    }

    /** Returns wire Bounds for a segment (H or V) with margin. */
    public static List<Bounds> segmentAsWireBounds(Location a, Location b, int margin) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY());
        int y1 = Math.max(a.getY(), b.getY());
        int w = Math.max(1, x1 - x0);
        int h = Math.max(1, y1 - y0);
        Bounds core = Bounds.create(x0, y0, w, h);
        return List.of(core.expand(Math.max(0, margin)));
    }

    /** Converts a polyline (path) into a list of wire Bounds with margin. */
    public static List<Bounds> polylineAsWireBounds(List<Location> poly, int margin) {
        List<Bounds> out = new ArrayList<>();
        for (int i = 0; i + 1 < poly.size(); i++) {
            out.addAll(segmentAsWireBounds(poly.get(i), poly.get(i + 1), margin));
        }
        return out;
    }

    /** Creates a launch pad at a grid distance outside the component, in the opposite direction of the facing (to exit). */
    public static Location launchPad(Location mouth, Direction facing, int grid, int steps) {
        int s = Math.max(1, steps) * Math.max(1, grid);
        int dx = 0, dy = 0;
        // salir “hacia afuera”: opuesto al facing del túnel
        if (facing == Direction.EAST)       dx = -s;
        else if (facing == Direction.WEST)  dx =  s;
        else if (facing == Direction.NORTH) dy =  s;
        else if (facing == Direction.SOUTH) dy = -s;
        return Location.create(mouth.getX() + dx, mouth.getY() + dy);
    }

    /** Reserve all the cells of a polyline for future penalties. */
    public static void markReservedPath(Set<Long> reserved, List<Location> poly, int grid) {
        if (reserved == null || poly == null || poly.size() < 2) return;
        for (Location p : poly) reserved.add(GridRouter.key(p));
        // densificar en pasos de grid para segmentos largos
        for (int i = 0; i + 1 < poly.size(); i++) {
            Location a = poly.get(i), b = poly.get(i + 1);
            if (a.getX() == b.getX()) {
                int x = a.getX();
                int y0 = Math.min(a.getY(), b.getY());
                int y1 = Math.max(a.getY(), b.getY());
                for (int y = y0; y <= y1; y += grid) {
                    reserved.add(GridRouter.key(Location.create(x, y)));
                }
            } else if (a.getY() == b.getY()) {
                int y = a.getY();
                int x0 = Math.min(a.getX(), b.getX());
                int x1 = Math.max(a.getX(), b.getX());
                for (int x = x0; x <= x1; x += grid) {
                    reserved.add(GridRouter.key(Location.create(x, y)));
                }
            }
        }
    }

    /** Direct Manhattan path (HV or VH) avoiding obstacle rectangles. */
    public static List<Location> tryManhattanClear(Location a, Location b, List<Bounds> obstacles, int clearHard) {
        // HV: a→(bx,ay)→b
        Location midH = Location.create(b.getX(), a.getY());
        if (pathClear(a, midH, obstacles, clearHard) && pathClear(midH, b, obstacles, clearHard)) {
            return List.of(a, midH, b);
        }
        // VH: a→(ax,by)→b
        Location midV = Location.create(a.getX(), b.getY());
        if (pathClear(a, midV, obstacles, clearHard) && pathClear(midV, b, obstacles, clearHard)) {
            return List.of(a, midV, b);
        }
        return null;
    }

    private static boolean pathClear(Location p, Location q, List<Bounds> obstacles, int clearHard) {
        java.awt.Rectangle segBox = segAabb(p, q).grow(clearHard);
        for (Bounds b : obstacles) {
            if (rectIntersects(b, segBox)) return false;
        }
        return true;
    }

    // AABB de un segmento manhattan
    private static Rectangle segAabb(Location a, Location b) {
        int x0 = Math.min(a.getX(), b.getX());
        int x1 = Math.max(a.getX(), b.getX());
        int y0 = Math.min(a.getY(), b.getY());
        int y1 = Math.max(a.getY(), b.getY());
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    // Rectangle con grow sencillo
    private static final class Rectangle extends java.awt.Rectangle {
        Rectangle(int x, int y, int w, int h) { super(x, y, w, h); }
        Rectangle grow(int m) { return new Rectangle(x - m, y - m, width + 2*m, height + 2*m); }
    }

    private static boolean rectIntersects(Bounds b, java.awt.Rectangle r) {
        return r.intersects(b.getX(), b.getY(), b.getWidth(), b.getHeight());
    }
}
