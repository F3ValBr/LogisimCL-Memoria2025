package com.cburch.logisim.verilog.layout;

import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.auxiliary.ModulePort;
import com.cburch.logisim.verilog.comp.auxiliary.PortEndpoint;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.NetBit;

import java.util.*;
import java.util.function.Function;

public final class ModuleNetIndex {
    // netId -> lista de refs compactos (int)
    private final Map<Integer, List<Integer>> byNet = new LinkedHashMap<>();

    // Guardamos referencias al modelo para poder resolver nombres/puertos
    private final List<VerilogCell> cells;
    private final List<ModulePort> modulePorts;

    // ---- Codificación del ref (32 bits) ----
    // bit31..30 = kind (0=cell, 1=top)
    // bit29..16 = ownerIdx (hasta 16383)
    // bit15.. 8 = portOrd (sólo para celdas; 0..255) / 0 para top
    // bit 7.. 0 = bitIdx (0..255)
    private static int encCell(int cellIdx, int portOrd, int bitIdx) {
        return (0 << 30) | (cellIdx << 16) | ((portOrd & 0xFF) << 8) | (bitIdx & 0xFF);
    }
    private static int encTop (int portIdx, int bitIdx) {
        return (1 << 30) | (portIdx << 16) | (bitIdx & 0xFF);
    }

    public static boolean isTop  (int ref){ return ((ref >>> 30) & 0b11) == 1; }
    public static int ownerIdx   (int ref){ return (ref >>> 16) & 0x3FFF; }
    public static int portOrd    (int ref){ return (ref >>> 8)  & 0xFF; }   // válido para celdas
    public static int bitIdx     (int ref){ return ref & 0xFF; }

    // ---- LUTs por celda ----
    // nombre de puerto -> ordinal (estable por celda)
    private final List<Map<String,Integer>> cellPortOrd = new ArrayList<>();
    // ordinal -> nombre de puerto
    private final List<List<String>> cellOrdToName = new ArrayList<>();
    // key (ord<<8 | bit) -> PortEndpoint
    private final List<Map<Integer,PortEndpoint>> cellEpByKey = new ArrayList<>();

    private static int key(int ord, int bit){ return ((ord & 0xFF) << 8) | (bit & 0xFF); }

    public ModuleNetIndex(List<VerilogCell> cells, List<ModulePort> modulePorts) {
        this.cells = Objects.requireNonNull(cells);
        this.modulePorts = Objects.requireNonNull(modulePorts);

        // 1) Puertos del módulo (top)
        for (int pIdx = 0; pIdx < modulePorts.size(); pIdx++) {
            ModulePort p = modulePorts.get(pIdx);
            for (int i = 0; i < p.width(); i++) {
                int net = p.netIdAt(i);
                if (net >= 0) add(net, encTop(pIdx, i)); // constantes (<0) no se indexan
            }
        }

        // 2) Celdas internas: construir LUTs y refs con portOrd embebido
        for (int cIdx = 0; cIdx < cells.size(); cIdx++) {
            VerilogCell c = cells.get(cIdx);

            Map<String,Integer> ordMap = new LinkedHashMap<>();
            List<String> namesByOrd     = new ArrayList<>();
            Map<Integer,PortEndpoint> epMap = new HashMap<>();

            // asigna ordinal incremental por nombre de puerto
            Function<String,Integer> ordOf = name -> ordMap.computeIfAbsent(name, n -> {
                int ord = namesByOrd.size();
                namesByOrd.add(n);
                return ord;
            });

            for (PortEndpoint ep : c.endpoints()) {
                var br = ep.getBitRef();
                if (br instanceof NetBit nb) {
                    int ord = ordOf.apply(ep.getPortName());
                    int b   = ep.getBitIndex();
                    epMap.put(key(ord, b), ep);
                    add(nb.getNetId(), encCell(cIdx, ord, b));
                }
            }

            cellPortOrd.add(ordMap);
            cellOrdToName.add(namesByOrd);
            cellEpByKey.add(epMap);
        }
    }

    private void add(int netId, int ref) {
        byNet.computeIfAbsent(netId, k -> new ArrayList<>()).add(ref);
    }

    // ---- API pública existente ----
    public Set<Integer> netIds() { return byNet.keySet(); }
    public List<Integer> endpointsOf(int netId) {
        return byNet.getOrDefault(netId, List.of());
    }

    // ---- NUEVO: resoluciones para “bus edges” ----

    /** Devuelve el índice de puerto top directamente del ref. */
    public int resolveTopPortIdx(int topRef) {
        if (!isTop(topRef)) throw new IllegalArgumentException("Ref no es top");
        return ownerIdx(topRef);
    }

    /** Devuelve el nombre de puerto de celda a partir del ref. */
    public Optional<String> resolveCellPortName(int cellRef) {
        if (isTop(cellRef)) return Optional.empty();
        int cIdx = ownerIdx(cellRef);
        int ord  = portOrd(cellRef);
        List<String> names = cellOrdToName.get(cIdx);
        if (ord < 0 || ord >= names.size()) return Optional.empty();
        return Optional.ofNullable(names.get(ord));
    }

    /** Devuelve el PortEndpoint (dirección incluida) para un ref de celda. */
    public Optional<PortEndpoint> resolveCellEndpoint(int cellRef) {
        if (isTop(cellRef)) return Optional.empty();
        int cIdx = ownerIdx(cellRef);
        int ord  = portOrd(cellRef);
        int bit  = bitIdx(cellRef);
        PortEndpoint ep = cellEpByKey.get(cIdx).get(key(ord, bit));
        return Optional.ofNullable(ep);
    }

    /** Acceso al modelo (por si lo necesitas para layout/adapters). */
    public VerilogCell cellAt(int idx){ return cells.get(idx); }
    public ModulePort topPortAt(int idx){ return modulePorts.get(idx); }

    /** Info agrupada de un puerto en una celda */
    public static final class CellPortInfo {
        private final String name;
        private final PortDirection portDirection;
        private final List<PortEndpoint> bits;

        public CellPortInfo(String name, PortDirection portDirection, List<PortEndpoint> bits) {
            this.name = name;
            this.portDirection = portDirection;
            this.bits = bits;
        }

        public String name() { return name; }
        public PortDirection direction() { return portDirection; }
        public int width() { return bits.size(); }
        public List<PortEndpoint> bits() { return bits; }
    }

    /** Devuelve todos los puertos de una celda, agrupados */
    public List<CellPortInfo> getCellPorts(int cellIdx) {
        VerilogCell cell = cellAt(cellIdx);

        // Agrupar por nombre de puerto
        Map<String,List<PortEndpoint>> grouped = new LinkedHashMap<>();
        for (PortEndpoint ep : cell.endpoints()) {
            grouped.computeIfAbsent(ep.getPortName(), k -> new ArrayList<>()).add(ep);
        }

        // Convertir en objetos de alto nivel
        List<CellPortInfo> infos = new ArrayList<>();
        for (var entry : grouped.entrySet()) {
            String portName = entry.getKey();
            List<PortEndpoint> eps = entry.getValue();
            PortDirection dir = eps.isEmpty() ? PortDirection.UNKNOWN : eps.get(0).getDirection();
            infos.add(new CellPortInfo(portName, dir, eps));
        }
        return infos;
    }
}

