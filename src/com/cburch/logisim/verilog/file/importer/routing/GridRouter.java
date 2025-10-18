package com.cburch.logisim.verilog.file.importer.routing;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Location;

import java.awt.*;
import java.util.*;
import java.util.List;

/**
 * Router A* en grilla Manhattan:
 * - Permite múltiples codos.
 * - Permite cruzar otros wires (no se consideran obstáculos duros).
 * - Evita componentes (obstáculos) con clearance “duro” y penaliza cercanía con clearance “blando”.
 * - Soporta “reservas” (celdas caras) para desincentivar rutar por encima de rutas previas.
 */
public final class GridRouter {
    private final int grid, clearSoft, clearHard, costNear, costReserved;
    private final List<Bounds> obstacles;
    private final Set<Long> reserved;

    // límites
    private int maxExpansions = 40000;
    private int maxQueue = 50000;
    private long maxMillis = 1200;

    public GridRouter(int grid, int clearSoft, int clearHard, int costNear, int costReserved,
                      List<Bounds> obstacles, Set<Long> reserved) {
        this.grid = Math.max(1, grid);
        this.clearSoft = Math.max(0, clearSoft);
        this.clearHard = Math.max(this.clearSoft, clearHard);
        this.costNear = Math.max(0, costNear);
        this.costReserved = Math.max(0, costReserved);
        this.obstacles = (obstacles == null) ? List.of() : obstacles;
        this.reserved = (reserved == null) ? new HashSet<>() : reserved;
    }

    public GridRouter withMaxExpansions(int v) { this.maxExpansions = Math.max(1000, v); return this; }
    public GridRouter withMaxQueue(int v)      { this.maxQueue = Math.max(2000, v); return this; }
    public GridRouter withMaxMillis(long v)    { this.maxMillis = Math.max(100, v); return this; }

    public List<Location> route(Location s, Location t) {
        Location src = snap(s);
        Location dst = snap(t);

        // Bounding box de búsqueda con margen
        Rectangle bbox = makeBBox(src, dst, /*pad*/ 120); // 120 px ~ 12 celdas a grid 10

        PriorityQueue<Node> pq = new PriorityQueue<>(Comparator.comparingInt(n -> n.f));
        Map<Long, Integer> dist = new HashMap<>();
        Map<Long, Long> parent = new HashMap<>();

        long keyS = key(src), keyT = key(dst);
        pq.add(new Node(src, 0, h(src, dst), -1));
        dist.put(keyS, 0);
        parent.put(keyS, -1L);

        int[] dx = { grid, -grid, 0, 0 };
        int[] dy = { 0, 0, grid, -grid };

        int expansions = 0;
        long deadline = System.currentTimeMillis() + maxMillis;

        while (!pq.isEmpty()) {
            if (expansions > maxExpansions) return null;
            if (pq.size() > maxQueue) return null;
            if (System.currentTimeMillis() > deadline) return null;

            Node cur = pq.poll();
            assert cur != null;
            if (cur.p.equals(dst)) break;

            expansions++;

            for (int dir = 0; dir < 4; dir++) {
                Location np = Location.create(cur.p.getX() + dx[dir], cur.p.getY() + dy[dir]);
                if (!bbox.contains(np.getX(), np.getY())) continue;
                if (blocked(np)) continue;

                int stepCost = 10 + nearPenalty(np) + reservedPenalty(np);

                // penaliza cambios de dirección para evitar zigzag
                int turn = (cur.dir != -1 && cur.dir != dir) ? TURN_COST : 0;
                int straight = (cur.dir == dir && cur.dir != -1) ? STRAIGHT_BONUS : 0;

                int nd = cur.g + stepCost + turn + straight;
                long kn = key(np);
                Integer old = dist.get(kn);
                if (old == null || nd < old) {
                    dist.put(kn, nd);
                    parent.put(kn, key(cur.p));
                    // f = g + h (admisible). Usamos nd para g, más heurística h
                    pq.add(new Node(np, nd, nd + h(np, dst), dir));
                }
            }

        }

        if (!parent.containsKey(keyT)) return null;
        List<Location> rev = new ArrayList<>();
        for (long k = keyT; k != -1L; k = parent.getOrDefault(k, -1L)) {
            rev.add(fromKey(k));
        }
        Collections.reverse(rev);
        return compress(rev);
    }

    private Rectangle makeBBox(Location a, Location b, int pad) {
        int x0 = Math.min(a.getX(), b.getX()) - pad;
        int x1 = Math.max(a.getX(), b.getX()) + pad;
        int y0 = Math.min(a.getY(), b.getY()) - pad;
        int y1 = Math.max(a.getY(), b.getY()) + pad;
        return new Rectangle(x0, y0, x1 - x0 + 1, y1 - y0 + 1);
    }

    private Location snap(Location p) {
        int x = ((p.getX() + grid/2) / grid) * grid;
        int y = ((p.getY() + grid/2) / grid) * grid;
        return Location.create(x, y);
    }

    private int h(Location a, Location b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }

    private boolean blocked(Location p) {
        Rectangle r = new Rectangle(p.getX() - clearHard, p.getY() - clearHard, 2*clearHard+1, 2*clearHard+1);
        for (Bounds b : obstacles) {
            if (rectIntersects(b, r)) return true;
        }
        return false;
    }

    private int nearPenalty(Location p) {
        Rectangle r = new Rectangle(p.getX() - clearSoft, p.getY() - clearSoft, 2*clearSoft+1, 2*clearSoft+1);
        for (Bounds b : obstacles) {
            if (rectIntersects(b, r)) return costNear;
        }
        return 0;
    }

    private int reservedPenalty(Location p) {
        return reserved.contains(key(p)) ? costReserved : 0;
    }

    private static boolean rectIntersects(Bounds b, Rectangle r) {
        return r.intersects(b.getX(), b.getY(), b.getWidth(), b.getHeight());
    }

    private static List<Location> compress(List<Location> pts) {
        if (pts.size() <= 2) return pts;
        List<Location> out = new ArrayList<>();
        out.add(pts.get(0));
        for (int i = 1; i + 1 < pts.size(); i++) {
            Location a = pts.get(i - 1), b = pts.get(i), c = pts.get(i + 1);
            boolean col = (a.getX() == b.getX() && b.getX() == c.getX())
                    || (a.getY() == b.getY() && b.getY() == c.getY());
            if (!col) out.add(b);
        }
        out.add(pts.get(pts.size() - 1));
        return out;
    }

    public static long key(Location p) {
        long x = p.getX() & 0xffffffffL;
        long y = p.getY() & 0xffffffffL;
        return (x << 32) | y;
    }
    private static Location fromKey(long k) {
        int x = (int)(k >>> 32);
        int y = (int)(k & 0xffffffffL);
        return Location.create(x, y);
    }

    // dentro de GridRouter
    private static final int TURN_COST = 20;   // súbelo si quieres aún menos giros
    private static final int STRAIGHT_BONUS = -2; // pequeño "descuento" si sigues recto

    /**
     * @param dir 0:+x, 1:-x, 2:+y, 3:-y, -1 inicio
     */
    private record Node(Location p, int g, int f, int dir) { }
}
