package com.cburch.logisim.verilog.file.importer.routing;

import com.cburch.logisim.data.Location;

import java.util.*;

/** Minimum Spanning Tree using Prim's algorithm with Manhattan distance. */
public final class MstPlanner {
    /** Returns list of edges {i,j} of a MST (Manhattan distance). */
    public static List<int[]> buildMstEdges(List<Location> pts) {
        int n = (pts == null) ? 0 : pts.size();
        List<int[]> out = new ArrayList<>();
        if (n < 2) return out;

        boolean[] used = new boolean[n];
        int[] best = new int[n];
        int[] pv = new int[n];

        Arrays.fill(best, Integer.MAX_VALUE);
        Arrays.fill(pv, -1);
        best[0] = 0;

        for (int it = 0; it < n; it++) {
            int v = -1;
            for (int i = 0; i < n; i++) if (!used[i] && (v == -1 || best[i] < best[v])) v = i;
            used[v] = true;
            if (pv[v] != -1) out.add(new int[]{pv[v], v});
            for (int u = 0; u < n; u++) if (!used[u]) {
                int d = manhattan(pts.get(v), pts.get(u));
                if (d < best[u]) { best[u] = d; pv[u] = v; }
            }
        }
        return out;
    }

    private static int manhattan(Location a, Location b) {
        return Math.abs(a.getX() - b.getX()) + Math.abs(a.getY() - b.getY());
    }
}

