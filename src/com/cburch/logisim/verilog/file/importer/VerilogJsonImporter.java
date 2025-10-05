package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.comp.EndData;
import com.cburch.logisim.data.*;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.arith.ArithmeticPortMapRegister;
import com.cburch.logisim.std.gates.GatesPortMapRegister;
import com.cburch.logisim.std.memory.MemoryPortMapRegister;
import com.cburch.logisim.std.plexers.PlexersPortMapRegister;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.yosys.YosysComponentsPortMapRegister;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.CellFactoryRegistry;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.BitRef;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.Const0;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleBuilder;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysJsonNetlist;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysModuleDTO;
import com.cburch.logisim.verilog.layout.LayoutUtils;
import com.cburch.logisim.verilog.layout.MemoryIndex;
import com.cburch.logisim.verilog.layout.ModuleNetIndex;
import com.cburch.logisim.verilog.layout.auxiliary.DefaultNodeSizer;
import com.cburch.logisim.verilog.layout.auxiliary.NodeSizer;
import com.cburch.logisim.verilog.layout.builder.LayoutBuilder;
import com.cburch.logisim.verilog.layout.builder.LayoutRunner;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.ComponentAdapterRegistry;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.Strings;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.elk.graph.ElkNode;

import java.awt.Graphics;
import java.util.*;
import java.util.stream.Collectors;

import static com.cburch.logisim.data.Direction.*;

public final class VerilogJsonImporter {

    private final CellFactoryRegistry registry;
    private final VerilogModuleBuilder builder;
    private final MemoryOpAdapter memoryAdapter = new MemoryOpAdapter();
    private final ComponentAdapterRegistry adapter = new ComponentAdapterRegistry()
            .register(new UnaryOpAdapter())
            .register(new BinaryOpAdapter())
            .register(new MuxOpAdapter())
            .register(new RegisterOpAdapter())
            .register(memoryAdapter)
            ;
    private final NodeSizer sizer = new DefaultNodeSizer(adapter);

    private static final int GRID  = 10;
    private static final int MIN_X = 100;
    private static final int MIN_Y = 100;
    private static final int PAD_X = 100; // separación horizontal respecto a las celdas

    public VerilogJsonImporter(CellFactoryRegistry registry) {
        this.registry = registry;
        this.builder = new VerilogModuleBuilder(registry);
    }

    public void importInto(Project proj) {
        System.out.println("Importing JSON Verilog...");

        // Bootstrap de port-maps (una sola vez por archivo)
        BuiltinPortMaps.initOnce(
                proj.getLogisimFile(),
                List.of(
                        new ArithmeticPortMapRegister(),
                        new GatesPortMapRegister(),
                        new MemoryPortMapRegister(),
                        new PlexersPortMapRegister(),
                        new YosysComponentsPortMapRegister()
                ));

        JsonNode root = proj.getLogisimFile().getLoader().JSONImportChooser(proj.getFrame());
        if (root == null) {
            System.out.println("Import cancelled.");
            return;
        }

        // Netlist y canvas
        YosysJsonNetlist netlist = YosysJsonNetlist.from(root);
        Canvas canvas = proj.getFrame().getCanvas();
        Graphics g = canvas.getGraphics(); // si es null, los adapters usan fallback

        int totalCells = 0;

        // Recorremos módulos del netlist
        for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) netlist.modules()::iterator) {
            // Construcción del módulo
            VerilogModuleImpl mod = builder.buildModule(dto);

            System.out.println("== Módulo: " + mod.name() + " ==");
            printModulePorts(mod);

            // Índices auxiliares (para layout y memorias)
            ModuleNetIndex netIndex = builder.buildNetIndex(mod);
            printNets(mod, netIndex);

            MemoryIndex memIndex = builder.buildMemoryIndex(mod);
            memoryAdapter.beginModule(memIndex, mod);
            printMemories(memIndex);

            // 1) Alias de celdas de memoria (rd/wr/init → representante)
            Map<VerilogCell, VerilogCell> cellAlias = buildMemoryCellAlias(mod, memIndex);

            // 2) Layout con alias (ELK)
            LayoutBuilder.Result elk = LayoutBuilder.build(proj, mod, netIndex, sizer, cellAlias);
            LayoutRunner.run(elk.root);
            LayoutUtils.applyLayoutAndClamp(elk.root, MIN_X, MIN_Y);

            // 3) Colocar pins top separados y guardar anchors
            Map<VerilogCell, InstanceHandle> cellHandles = new HashMap<>();
            Map<ModulePort, PortAnchor>      topAnchors  = new HashMap<>();
            addModulePortsToCircuitSeparated(proj, canvas.getCircuit(), mod, elk, netIndex, g, topAnchors);

            // 4) Instanciar sólo celdas no-aliased y guardar sus PortGeom
            for (int i = 0; i < mod.cells().size(); i++) {
                VerilogCell cell = mod.cells().get(i);
                if (cellAlias.containsKey(cell)) continue;

                ElkNode n = elk.cellNode.get(cell);
                int x = (n == null) ? snap(MIN_X) : snap((int) Math.round(n.getX()));
                int y = (n == null) ? snap(MIN_Y) : snap((int) Math.round(n.getY()));

                // Pequeño offset de 10 si lo estabas usando:
                InstanceHandle h = adapter.create(canvas, g, cell, Location.create(x, y + 10));
                cellHandles.put(cell, h);
                totalCells++;
            }

            // 5) Etiquetas preferidas por netId (top name si existe)
            Map<Integer, String> netLabel = buildNetLabels(mod); // (por si luego lo usas externamente)

            // ========= NUEVO: BATCH por MÓDULO =========
            ImportBatch batch = new ImportBatch(canvas.getCircuit());

            // 6) Túneles y cables (por puertos y endpoints)
            placePortBasedTunnels(
                    batch, proj, canvas.getCircuit(), mod, elk,
                    cellHandles, topAnchors, g
            );

            // 7) Constantes (drivers reales) usando PortEndpoint/ModulePort
            placeConstantDriversForModule(
                    batch, proj, canvas.getCircuit(), mod, elk,
                    cellHandles, topAnchors, g
            );

            // 8) Commit único del módulo (evita ConcurrentModification)
            batch.commit(proj, "addComponentsFromImport");

            System.out.println();
        }

        System.out.println("Total de celdas procesadas: " + totalCells);
        System.out.println("Done.");
    }

    /* ===================== Helpers ===================== */

    /** Construye el mapa de alias: cada $memrd/$memwr/$meminit apunta a su representante. */
    private static Map<VerilogCell, VerilogCell> buildMemoryCellAlias(VerilogModuleImpl mod, MemoryIndex memIndex) {
        Map<VerilogCell, VerilogCell> alias = new HashMap<>();

        for (LogicalMemory lm : memIndex.memories()) {
            int arrIdx = lm.arrayCellIdx();
            VerilogCell rep = null;

            if (arrIdx >= 0) {
                rep = mod.cells().get(arrIdx); // $mem / $mem_v2 → representante
            } else {
                Integer idx = !lm.readPortIdxs().isEmpty()
                        ? lm.readPortIdxs().get(0)
                        : (!lm.writePortIdxs().isEmpty() ? lm.writePortIdxs().get(0) : null);
                if (idx != null) rep = mod.cells().get(idx);
            }
            if (rep == null) continue;

            for (Integer i : lm.readPortIdxs())  { VerilogCell c = mod.cells().get(i); if (c != rep) alias.put(c, rep); }
            for (Integer i : lm.writePortIdxs()) { VerilogCell c = mod.cells().get(i); if (c != rep) alias.put(c, rep); }
            for (Integer i : lm.initIdxs())      { VerilogCell c = mod.cells().get(i); if (c != rep) alias.put(c, rep); }
        }
        return alias;
    }

    private static int snap(int v){ return (v/GRID)*GRID; }

    /** Crea pins top (inputs a la izquierda, outputs a la derecha) y devuelve anchors (loc+facing). */
    private void addModulePortsToCircuitSeparated(Project proj,
                                                  Circuit circuit,
                                                  VerilogModuleImpl mod,
                                                  LayoutBuilder.Result elk,
                                                  ModuleNetIndex netIdx,
                                                  Graphics g,
                                                  Map<ModulePort, PortAnchor> topAnchors) {
        // 1) Caja envolvente de las celdas del módulo
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (VerilogCell c : mod.cells()) {
            ElkNode n = elk.cellNode.get(c);
            if (n == null) continue;
            int x0 = (int)Math.round(n.getX());
            int y0 = (int)Math.round(n.getY());
            int x1 = x0 + (int)Math.round(n.getWidth());
            int y1 = y0 + (int)Math.round(n.getHeight());
            minX = Math.min(minX, x0);
            minY = Math.min(minY, y0);
            maxX = Math.max(maxX, x1);
            maxY = Math.max(maxY, y1);
        }
        if (minX == Integer.MAX_VALUE) { // módulo vacío
            minX = MIN_X; minY = MIN_Y; maxX = MIN_X + 100; maxY = MIN_Y + 100;
        }

        // 2) Separación horizontal
        final int xInputs  = snap(minX - PAD_X);
        final int xOutputs = snap(maxX + PAD_X);

        // 3) Fila vertical para inputs (simple): repartir entre minY..maxY
        List<ModulePort> inputs  = mod.ports().stream()
                .filter(p -> p.direction() == PortDirection.INPUT).toList();
        List<ModulePort> outputs = mod.ports().stream()
                .filter(p -> p.direction() == PortDirection.OUTPUT).toList();

        int spanY = Math.max(1, maxY - minY);
        int inStep = Math.max(GRID, spanY / Math.max(1, inputs.size() + 1));
        int curInY = minY + inStep;

        // 4) Para outputs, calculamos Y a partir de los nodos internos conectados
        Map<ModulePort, Integer> outY = new HashMap<>();
        for (ModulePort p : outputs) {
            // Recolectar Y de nodos internos conectados a CUALQUIER bit del puerto
            List<Integer> ys = new ArrayList<>();

            // Para cada net del módulo, si contiene un endpoint top de este puerto, añade
            for (int netId : netIdx.netIds()) {
                List<Integer> refs = netIdx.endpointsOf(netId);
                if (refs == null || refs.isEmpty()) continue;

                boolean touches = false;
                for (int ref : refs) {
                    if (ModuleNetIndex.isTop(ref)) {
                        int pIdx = netIdx.resolveTopPortIdx(ref);
                        if (pIdx >= 0 && pIdx < mod.ports().size() && mod.ports().get(pIdx) == p) {
                            touches = true;
                            break;
                        }
                    }
                }
                if (!touches) continue;

                // Extrae Y de los endpoints internos de esta net
                for (int ref : refs) {
                    if (!ModuleNetIndex.isTop(ref)) {
                        int cellIdx = ModuleNetIndex.ownerIdx(ref);
                        VerilogCell cell = mod.cells().get(cellIdx);
                        ElkNode n = elk.cellNode.get(cell);
                        if (n != null) {
                            int y = snap((int)Math.round(n.getY() + n.getHeight()/2.0));
                            ys.add(y);
                        }
                    }
                }
            }

            if (!ys.isEmpty()) {
                ys.sort(Integer::compare);
                int y, m = ys.size();
                y = (m % 2 == 1) ? ys.get(m/2) : (ys.get(m/2 - 1) + ys.get(m/2)) / 2;
                outY.put(p, snap(y));
            } else {
                // Fallback: centro vertical del bloque
                outY.put(p, snap((minY + maxY) / 2));
            }
        }

        // Inputs
        for (ModulePort p : inputs) {
            ComponentFactory pinFactory = Pin.FACTORY;
            AttributeSet attrs = pinFactory.createAttributeSet();

            attrs.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, p.width())));
            attrs.setValue(Pin.ATTR_TYPE, false);
            attrs.setValue(Pin.ATTR_TRISTATE, false);
            attrs.setValue(StdAttr.FACING, EAST);
            attrs.setValue(StdAttr.LABEL, p.name());

            Location loc = Location.create(snap(xInputs), snap(curInY));
            curInY += inStep;

            try {
                Component comp = addComponentSafe(proj, circuit, g, pinFactory, loc, attrs);
                topAnchors.put(p, new PortAnchor(comp.getLocation(), EAST));
            } catch (CircuitException e) {
                throw new IllegalStateException("No se pudo añadir pin input '" + p.name() + "'", e);
            }
        }

        // Outputs
        for (ModulePort p : outputs) {
            ComponentFactory pinFactory = Pin.FACTORY;
            AttributeSet attrs = pinFactory.createAttributeSet();

            attrs.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, p.width())));
            attrs.setValue(Pin.ATTR_TYPE, true);
            attrs.setValue(Pin.ATTR_TRISTATE, false);
            attrs.setValue(StdAttr.FACING, WEST);
            attrs.setValue(StdAttr.LABEL, p.name());

            int y = outY.getOrDefault(p, snap((minY + maxY) / 2));
            Location loc = Location.create(snap(xOutputs), y - 10);

            try {
                Component comp = addComponentSafe(proj, circuit, g, pinFactory, loc, attrs);
                topAnchors.put(p, new PortAnchor(comp.getLocation(), WEST));
            } catch (CircuitException e) {
                throw new IllegalStateException("No se pudo añadir pin output '" + p.name() + "'", e);
            }
        }
    }

    private static Component addComponentSafe(Project proj,
                                              Circuit circ,
                                              Graphics g,
                                              ComponentFactory factory,
                                              Location where,
                                              AttributeSet attrs) throws CircuitException {
        Component comp = factory.createComponent(where, attrs);
        if (circ.hasConflict(comp)) throw new CircuitException(Strings.get("exclusiveError"));

        Bounds b = comp.getBounds(g);
        int shiftX = 0, shiftY = 0;
        if (b.getX() < MIN_X) shiftX = MIN_X - b.getX();
        if (b.getY() < MIN_Y) shiftY = MIN_Y - b.getY();
        if (shiftX != 0 || shiftY != 0) {
            where = Location.create(where.getX() + snap(shiftX), where.getY() + snap(shiftY));
            comp = factory.createComponent(where, attrs);
            b = comp.getBounds(g);
        }
        if (b.getX() < 0 || b.getY() < 0) throw new CircuitException(Strings.get("negativeCoordError"));

        CircuitMutation m = new CircuitMutation(circ);
        m.add(comp);
        proj.doAction(m.toAction(Strings.getter("addComponentAction", factory.getDisplayGetter())));
        return comp;
    }

    private static void queueWire(ImportBatch batch, Location a, Location b) {
        batch.add(Wire.create(a, b));
    }

    /* ===================== Túneles con endpoints ===================== */

    /** Punto de conexión (coordenada y orientación) */
    private static final class PortAnchor {
        final Location loc;
        final Direction facing;
        PortAnchor(Location loc, Direction facing) {
            this.loc = loc; this.facing = facing;
        }
    }

    /** Etiquetas preferidas: si un netId toca un puerto top, usa ese nombre; si no, N{netId}. */
    private static Map<Integer,String> buildNetLabels(VerilogModuleImpl mod) {
        Map<Integer,String> netLabel = new HashMap<>();
        // 1) Prioriza nombres de puertos top
        for (int i = 0; i < mod.ports().size(); i++) {
            ModulePort p = mod.ports().get(i);
            int[] ids = p.netIds();
            for (int nid : ids) {
                if (nid >= 0) netLabel.putIfAbsent(nid, p.name());
            }
        }
        // 2) Fallback por netId vistos en endpoints internos
        Set<Integer> seen = new HashSet<>(netLabel.keySet());
        for (VerilogCell c : mod.cells()) {
            for (PortEndpoint ep : c.endpoints()) {
                Integer nid = ep.getNetIdOrNull();
                if (nid != null && !seen.contains(nid)) {
                    netLabel.put(nid, "N" + nid);
                    seen.add(nid);
                }
            }
        }
        return netLabel;
    }

    /** Info de bus agrupada por puerto. */
    private static final class PortBusInfo {
        final int width;
        final int minNet;
        final int maxNet;
        final SortedSet<Integer> nets; // por si lo necesitas
        final SortedSet<Integer> bits; // índices de bit presentes
        PortBusInfo(int width, int minNet, int maxNet,
                    SortedSet<Integer> nets, SortedSet<Integer> bits) {
            this.width = width; this.minNet = minNet; this.maxNet = maxNet;
            this.nets = nets; this.bits = bits;
        }
    }

    /** Calcula (width, minNet, maxNet, ...) para un puerto de CELDA usando PortEndpoint. */
    private static Optional<PortBusInfo> computeCellPortBusInfo(VerilogCell cell, String portName) {
        SortedSet<Integer> nets = new TreeSet<>();
        SortedSet<Integer> bits = new TreeSet<>();
        for (PortEndpoint ep : cell.endpoints()) {
            if (!portName.equals(ep.getPortName())) continue;
            Integer nid = ep.getNetIdOrNull(); // null si es constante
            if (nid == null) continue;
            nets.add(nid);
            bits.add(ep.getBitIndex());
        }
        if (nets.isEmpty()) return Optional.empty();
        return Optional.of(new PortBusInfo(nets.size(), nets.first(), nets.last(), nets, bits));
    }

    /** Calcula (width, minNet, maxNet, ...) para un puerto TOP usando ModulePort.netIds(). */
    private static Optional<PortBusInfo> computeTopPortBusInfo(ModulePort p) {
        SortedSet<Integer> nets = new TreeSet<>();
        SortedSet<Integer> bits = new TreeSet<>();
        int[] arr = p.netIds();
        for (int i = 0; i < arr.length; i++) {
            int nid = arr[i];
            if (nid >= 0) {           // ignora const (-1/-2/…)
                nets.add(nid);
                bits.add(i);
            }
        }
        if (nets.isEmpty()) return Optional.empty();
        return Optional.of(new PortBusInfo(nets.size(), nets.first(), nets.last(), nets, bits));
    }

    /** Formato de etiqueta pedido: N<min>-<max> (p.ej. N11-13). */
    private static String makeRangeLabel(int minNet, int maxNet) {
        return (minNet == maxNet) ? ("N" + minNet) : ("N" + minNet + "-" + maxNet);
    }

    /** Túneles por puerto usando PortEndpoint/ModulePort:
     *  - un túnel multibit por puerto
     *  - etiqueta N<min>-<max>
     *  - facing por borde más cercano (N/E/S/W)
     *  - SIEMPRE un cable corto entre la boca y el túnel (como con constantes), todo en BATCH
     */
    private void placePortBasedTunnels(ImportBatch batch,
                                       Project proj,
                                       Circuit circuit,
                                       VerilogModuleImpl mod,
                                       LayoutBuilder.Result elk,
                                       Map<VerilogCell, InstanceHandle> cellHandles,
                                       Map<ModulePort, PortAnchor> topAnchors,
                                       Graphics g) {

        // evita duplicados exactos (posición real del túnel y label)
        record Key(int x, int y, String label) {}
        Set<Key> placed = new HashSet<>();

        // 1) TOP ports
        for (ModulePort p : mod.ports()) {
            PortAnchor anc = topAnchors.get(p);
            if (anc == null) continue;

            var infoOpt = computeTopPortBusInfo(p);
            if (infoOpt.isEmpty()) continue;
            PortBusInfo info = infoOpt.get();

            String label = makeRangeLabel(info.minNet, info.maxNet);
            int tunnelWidth = Math.max(1, Math.min(p.width(), info.width));

            // Queremos que el túnel “mire hacia afuera” del bloque; si el Pin mira EAST (hacia el bloque),
            // el túnel lo ponemos WEST, y viceversa (tal como ya hacías).
            Direction facing = (anc.facing == EAST) ? WEST : EAST;

            // Donde realmente quedará el TÚNEL (kLoc)
            int step = GRID;
            int kx = anc.loc.getX() + (facing == EAST ?  step :
                    facing == WEST ? -step : 0);
            int ky = anc.loc.getY() + (facing == SOUTH ?  step :
                    facing == NORTH ? -step : 0);

            Key k = new Key(kx, ky, label);
            if (placed.add(k)) {
                createTunnelWithWireNear(batch, proj, g, anc.loc, tunnelWidth, label, facing);
            }
        }

        // 2) Celdas internas: un túnel por puerto de cada celda
        for (Map.Entry<VerilogCell, InstanceHandle> e : cellHandles.entrySet()) {
            VerilogCell cell = e.getKey();
            InstanceHandle ih = e.getValue();
            if (ih == null || ih.ports == null) continue;

            for (String portName : cell.getPortNames()) {
                var infoOpt = computeCellPortBusInfo(cell, portName);
                if (infoOpt.isEmpty()) continue;
                PortBusInfo info = infoOpt.get();

                Location pinLoc = ih.ports.locateByName(portName);
                if (pinLoc == null) continue;

                int declWidth = Math.max(1, cell.portWidth(portName));
                int tunnelWidth = Math.max(1, Math.min(declWidth, info.width));
                String label = makeRangeLabel(info.minNet, info.maxNet);

                // Facing por borde más cercano (N/E/S/W) – tu heurística intacta
                Direction facing = facingByNearestBorder(ih.component.getBounds(g), pinLoc);

                // Donde realmente quedará el TÚNEL (kLoc)
                int step = GRID;
                int kx = pinLoc.getX() + (facing == EAST ?  step :
                        facing == WEST ? -step : 0);
                int ky = pinLoc.getY() + (facing == SOUTH ?  step :
                        facing == NORTH ? -step : 0);

                Key k = new Key(kx, ky, label);
                if (placed.add(k)) {
                    createTunnelWithWireNear(batch, proj, g, pinLoc, tunnelWidth, label, facing);
                }
            }
        }
    }

    // === Helper: crea TÚNEL con un cable corto desde la boca del pin, TODO en batch ===
    private void createTunnelWithWireNear(ImportBatch batch,
                                          Project proj,
                                          Graphics g,
                                          Location pinMouthLoc,
                                          int width,
                                          String label,
                                          Direction facing) {
        final int step = GRID;
        int dx = 0, dy = 0;
        if (facing == EAST)       dx = -step;
        else if (facing == WEST)  dx =  step;
        else if (facing == NORTH) dy =  step;
        else if (facing == SOUTH) dy = -step;

        final Location kLoc = Location.create(pinMouthLoc.getX() + dx, pinMouthLoc.getY() + dy);

        // 1) Encola wire primero
        queueWire(batch, pinMouthLoc, kLoc);

        // 2) Prepara attrs del túnel
        AttributeSet a = com.cburch.logisim.std.wiring.Tunnel.FACTORY.createAttributeSet();
        a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
        a.setValue(StdAttr.LABEL, label);
        a.setValue(StdAttr.FACING, facing);

        // 3) Coloca el túnel para que su pin caiga en kLoc (ajusta offset)
        Component probe = com.cburch.logisim.std.wiring.Tunnel.FACTORY.createComponent(Location.create(0, 0), a);
        EndData end0   = probe.getEnd(0);
        int offX = end0.getLocation().getX() - probe.getLocation().getX();
        int offY = end0.getLocation().getY() - probe.getLocation().getY();
        Location tunnelLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

        // 4) Encola el túnel
        try {
            batch.add(com.cburch.logisim.std.wiring.Tunnel.FACTORY.createComponent(tunnelLoc, a));
        } catch (Throwable ex) {
            System.err.println("No se pudo encolar túnel '" + label + "': " + ex.getMessage());
        }
    }

    /* ===================== CONSTANTES: drivers reales ===================== */
    /**
     * Crea componentes Constant y los cablea a los pines cuya conexión es constante.
     * - Para cada puerto de celda/top:
     *   * Si TODOS sus bits son 0/1 → una sola Constant multibit.
     *   * Si mezcla → Constant(1) por cada bit 0/1.
     * - Bits X/Z no generan drivers (no hay fuente tri/unknown aquí).
     * - TODO en BATCH.
     */
    private void placeConstantDriversForModule(ImportBatch batch,
                                               Project proj,
                                               Circuit circuit,
                                               VerilogModuleImpl mod,
                                               LayoutBuilder.Result elk,
                                               Map<VerilogCell, InstanceHandle> cellHandles,
                                               Map<ModulePort, PortAnchor> topAnchors,
                                               Graphics g) {
        // ===== 1) Celdas internas =====
        for (VerilogCell cell : mod.cells()) {
            InstanceHandle ih = cellHandles.get(cell);
            if (ih == null || ih.ports == null) continue;

            // Agrupa endpoints por nombre de puerto → lista ordenada por bitIndex
            Map<String, List<PortEndpoint>> byPort = new LinkedHashMap<>();
            for (PortEndpoint ep : cell.endpoints()) {
                byPort.computeIfAbsent(ep.getPortName(), __ -> new ArrayList<>()).add(ep);
            }
            for (var e : byPort.entrySet()) {
                String pName = e.getKey();
                List<PortEndpoint> eps = e.getValue();
                int width = Math.max(1, cell.portWidth(pName));
                if (width <= 0) continue;

                // Normaliza a array por índice (si faltan, los marca null)
                PortEndpoint[] byIdx = new PortEndpoint[width];
                for (PortEndpoint ep : eps) {
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < width) byIdx[i] = ep;
                }

                // ¿todos los bits presentes y 0/1?
                boolean allPresent = true, all01 = true;
                int acc = 0; // LSB = bitIndex 0
                for (int i = 0; i < width; i++) {
                    PortEndpoint ep = byIdx[i];
                    if (ep == null) { allPresent = false; all01 = false; break; }
                    String k = constKind(ep.getBitRef());
                    if (k == null) { all01 = false; }
                    else if (k.equals("0") || k.equals("1")) {
                        if (k.equals("1")) acc |= (1 << i);
                    } else {
                        // x/z → no podemos generar Constant que los represente
                        all01 = false;
                    }
                }

                // Boca del pin (centro exacto ya en grilla)
                Location pinLoc = ih.ports.locateByName(pName);
                if (pinLoc == null) continue;

                // Facing por borde más cercano (tu heurística intacta)
                Direction facing = facingByNearestBorder(ih.component.getBounds(g), pinLoc);

                if (allPresent && all01) {
                    // === Caso compacto: una sola Constant multibit ===
                    createConstantDriverNear(batch, proj, circuit, g, pinLoc, width, acc, facing);
                } else {
                    // === Mixto: Constant(1) solo para bits 0/1 ===
                    for (int i = 0; i < width; i++) {
                        PortEndpoint ep = byIdx[i];
                        if (ep == null) continue;
                        String k = constKind(ep.getBitRef());
                        if (!"0".equals(k) && !"1".equals(k)) continue;

                        int bitVal = "1".equals(k) ? 1 : 0;
                        Location bitLoc = pinLoc; // misma boca (bus), sirve
                        createConstantDriverNear(batch, proj, circuit, g, bitLoc, 1, bitVal, facing);
                    }
                }
            }
        }

        // ===== 2) Puertos top =====
        for (ModulePort p : mod.ports()) {
            PortAnchor anchor = topAnchors.get(p);
            if (anchor == null) continue;
            int width = Math.max(1, p.width());
            int[] ids = p.netIds();
            if (ids == null || ids.length != width) continue;

            boolean all01 = true;
            int acc = 0; // LSB = índice 0
            for (int i = 0; i < width; i++) {
                int id = ids[i];
                if (id == ModulePort.CONST_0) { /* 0 */ }
                else if (id == ModulePort.CONST_1) { acc |= (1 << i); }
                else { all01 = false; } // incluye X/Z/Net
            }

            // Invertimos facing como ya hacías para top pins
            Direction facing = (anchor.facing == EAST) ? WEST : EAST;

            if (all01) {
                // Una sola Constant multibit
                createConstantDriverNear(batch, proj, circuit, g, anchor.loc, width, acc, facing);
            } else {
                // Constant(1) solo por bits 0/1
                for (int i = 0; i < width; i++) {
                    int id = ids[i];
                    if (id != ModulePort.CONST_0 && id != ModulePort.CONST_1) continue;
                    int bitVal = (id == ModulePort.CONST_1) ? 1 : 0;

                    // Evita apilar justo encima: desplaza en Y por bit (grilla)
                    Location bitLoc = Location.create(anchor.loc.getX(),
                            anchor.loc.getY() + i * GRID);
                    createConstantDriverNear(batch, proj, circuit, g, bitLoc, 1, bitVal, facing);
                }
            }
        }
    }

    /** Devuelve "0"/"1" si el BitRef es Const0/Const1; "x"/"z" si lo son; null si no es constante. */
    private static String constKind(BitRef br) {
        if (br == null) return null;
        if (br instanceof Const0) return "0";
        // Si tus clases son distintas, ajusta estos instanceof o nombres:
        return switch (br.getClass().getSimpleName()) {
            case "Const1" -> "1";
            case "ConstX" -> "x";
            case "ConstZ" -> "z";
            default -> null;
        };
    }

    /** Heurística N/E/S/W según borde más cercano (idéntica a la que ya usas). */
    private static Direction facingByNearestBorder(Bounds cb, Location pinLoc) {
        int left   = cb.getX();
        int right  = cb.getX() + cb.getWidth();
        int top    = cb.getY();
        int bottom = cb.getY() + cb.getHeight();

        int dxL = Math.abs(pinLoc.getX() - left);
        int dxR = Math.abs(pinLoc.getX() - right);
        int dyT = Math.abs(pinLoc.getY() - top);
        int dyB = Math.abs(pinLoc.getY() - bottom);

        Direction facing;
        int min = dxL; facing = EAST;
        if (dxR < min) { min = dxR; facing = WEST; }
        if (dyT < min) { min = dyT; facing = SOUTH; }
        if (dyB < min) { /*min = dyB;*/ facing = NORTH; }
        return facing;
    }

    /** Inserta una constante conectada a la "boca" loc. Crea primero el wire y luego la constante, en BATCH. */
    private void createConstantDriverNear(ImportBatch batch,
                                          Project proj,
                                          Circuit circuit,
                                          Graphics g,
                                          Location loc,        // boca del pin/bus destino
                                          int width,           // >=1
                                          int value,           // LSB=bit0
                                          Direction facing) {
        try {
            final int step = GRID; // típicamente 10

            // 1) Punto kLoc un grid "hacia atrás" respecto al facing del pin/túnel
            int dx = 0, dy = 0;
            if (facing == EAST)       dx = -step;
            else if (facing == WEST)  dx =  step;
            else if (facing == NORTH) dy =  step;
            else if (facing == SOUTH) dy = -step;

            final Location kLoc = Location.create(loc.getX() + dx, loc.getY() + dy);

            // 2) Prepara Constant (attrs) con WIDTH y VALUE
            LogisimFile lf = proj.getLogisimFile();
            if (lf == null) return;
            Library wiring = lf.getLibrary("Wiring");
            ComponentFactory constF = FactoryLookup.findFactory(wiring, "Constant");
            if (constF == null) return;

            AttributeSet a = constF.createAttributeSet();
            try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) {}
            try { a.setValue(StdAttr.FACING, facing); } catch (Exception ignore) {}

            // Valor (usa helper tolerante)
            boolean setOk = false;
            try { setConstantValueFlexible(a, width, value); setOk = true; } catch (Throwable ignore) {}
            if (!setOk) {
                String hex = "0x" + Integer.toHexString(value);
                setParsedByName(a, "value", hex);
            }

            // 3) Calcula offset del pin de salida de Constant para que caiga EXACTO en kLoc
            Component probe = constF.createComponent(Location.create(0, 0), a);
            EndData outEnd  = probe.getEnd(0); // Constant tiene 1 pin
            int offX = outEnd.getLocation().getX() - probe.getLocation().getX();
            int offY = outEnd.getLocation().getY() - probe.getLocation().getY();
            Location constLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

            // 4) Encolar wire primero (kLoc -> loc)
            batch.add(Wire.create(kLoc, loc));

            // 5) Encolar la constante (su pin cae en kLoc y queda conectado al wire)
            Component k = constF.createComponent(constLoc, a);
            batch.add(k);

        } catch (Exception ignore) {
            // Silencioso para no abortar el import completo
        }
    }

    @SuppressWarnings({ "rawtypes", "unchecked" })
    private void setConstantValueFlexible(AttributeSet a, int width, int value) {
        // enmascara al ancho
        int mask = (width >= 32) ? -1 : ((1 << Math.max(0, width)) - 1);
        int masked = value & mask;

        // 1) intenta como Integer (muchos Logisim tienen ATTR_VALUE Integer)
        try {
            Attribute attr = com.cburch.logisim.std.wiring.Constant.ATTR_VALUE;
            a.setValue(attr, Integer.valueOf(masked));
            return;
        } catch (Throwable ignore) { /* sigue */ }

        // 2) intenta como Value (algunas variantes usan Attribute<Value>)
        try {
            Attribute attr = com.cburch.logisim.std.wiring.Constant.ATTR_VALUE;
            Value val = Value.createKnown(BitWidth.create(width), masked);
            a.setValue(attr, val);
            return;
        } catch (Throwable ignore) { /* sigue */ }

        // 3) fallback: texto
        String hex = "0x" + Integer.toHexString(masked);
        setParsedByName(a, "value", hex);
    }

    /** Helper para setear por nombre si tu build lo soporta. */
    private static void setParsedByName(AttributeSet a, String name, String token) {
        try {
            for (Object ao : a.getAttributes()) {
                @SuppressWarnings("unchecked")
                Attribute<Object> attr = (Attribute<Object>) ao;
                if (attr.getName().equalsIgnoreCase(name)) {
                    a.setValue(attr, attr.parse(token));
                    return;
                }
            }
        } catch (Throwable ignore) { }
    }

    /* =========================
       Helpers de impresión
       ========================= */

    private static void printModulePorts(VerilogModuleImpl mod) {
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

    private static void printNets(VerilogModuleImpl mod, ModuleNetIndex idx) {
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

    private static void printMemories(MemoryIndex memIndex) {
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
}
