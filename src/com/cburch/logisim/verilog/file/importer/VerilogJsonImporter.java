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
import com.cburch.logisim.std.wiring.Constant;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.std.yosys.YosysComponentsPortMapRegister;
import com.cburch.logisim.verilog.comp.CellFactoryRegistry;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.BitRef;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.Const0;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleBuilder;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.file.JsonSynthFile;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysJsonNetlist;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysModuleDTO;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.file.ui.ImportCompletionDialog;
import com.cburch.logisim.verilog.file.ui.MissingModuleDialog;
import com.cburch.logisim.verilog.file.ui.NavigationHelper;
import com.cburch.logisim.verilog.file.ui.WarningCollector;
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
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;
import com.fasterxml.jackson.databind.JsonNode;
import org.eclipse.elk.graph.ElkNode;

import java.awt.Graphics;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static com.cburch.logisim.data.Direction.*;
import static com.cburch.logisim.verilog.file.importer.ImporterUtils.*;
import static com.cburch.logisim.verilog.file.ui.WarningCollector.showXWarningDialogLater;
import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.setParsedByName;
import static com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter.circuitHasAnyComponent;

public final class VerilogJsonImporter {

    private Path baseDir;                           // carpeta del JSON

    private final CellFactoryRegistry registry;
    private final VerilogModuleBuilder builder;
    private final MemoryOpAdapter memoryAdapter = new MemoryOpAdapter();
    private final ComponentAdapterRegistry adapter = new ComponentAdapterRegistry()
            .register(new UnaryOpAdapter())
            .register(new BinaryOpAdapter())
            .register(new MuxOpAdapter())
            .register(new RegisterOpAdapter())
            .register(memoryAdapter)
            .register(new ModuleBlackBoxAdapter(createFileSystemMaterializer()))
            ;
    private final NodeSizer sizer = new DefaultNodeSizer(adapter);
    private final WarningCollector xWarnings = new WarningCollector();

    static final int GRID  = 10;
    static final int MIN_X = 100;
    static final int MIN_Y = 100;
    static final int SEPARATION_INPUT_CELLS = 150; // separación horizontal entre inputs y celdas
    static final int PAD_X = SEPARATION_INPUT_CELLS + 100; // separación horizontal respecto a las celdas

    private volatile boolean importAllRemaining = false; // si el usuario elige “importar todo” en MissingModuleDialog

    public VerilogJsonImporter(CellFactoryRegistry registry) {
        this.registry = registry;
        this.builder = new VerilogModuleBuilder(registry);
    }

    public void importInto(Project proj) {
        System.out.println("Importing JSON Verilog...");
        BuiltinPortMaps.initOnce(
                proj.getLogisimFile(),
                List.of(
                        new ArithmeticPortMapRegister(),
                        new GatesPortMapRegister(),
                        new MemoryPortMapRegister(),
                        new PlexersPortMapRegister(),
                        new YosysComponentsPortMapRegister()
                ));

        xWarnings.clear();

        // 1) Elegir archivo y recordar carpeta base
        var res = proj.getLogisimFile().getLoader().JSONImportChooserWithPath(proj.getFrame());
        if (res == null || res.root() == null || res.path() == null) {
            System.out.println("Import cancelled.");
            return;
        }
        // === Contexto de importación / materialización ===
        // JSON actualmente importado (ruta)
        Path primaryJson                = res.path();                           // Path del JSON principal
        YosysJsonNetlist currentNetlist = YosysJsonNetlist.from(res.root());    // netlist del JSON principal
        this.baseDir                    = primaryJson.getParent();              // Carpeta base

        // 3) Importar todo el netlist (crea un circuito por módulo)
        Circuit mainCirc = doImportNetlist(proj, currentNetlist);

        // 4) Tras importar, construir map nombre→Circuit para el diálogo
        // Mostrar diálogo sólo si tenemos un circuito principal
        if (mainCirc != null) {
            var choice = ImportCompletionDialog.show(proj.getFrame(), mainCirc.getName());
            if (choice == ImportCompletionDialog.Choice.GO_TO_MODULE) {
                boolean ok = NavigationHelper.switchToCircuit(proj, mainCirc);
                if (!ok) {
                    NavigationHelper.showManualSwitchHint(proj, mainCirc);
                }
            }
        }

        if (xWarnings.hasWarnings()) {
            showXWarningDialogLater(proj, xWarnings);
        }
    }

    /* ===== Helpers ===== */

    // == Tu bucle actual de importación por módulos, factorízalo aquí ==
    private Circuit doImportNetlist(Project proj, YosysJsonNetlist netlist) {
        // Graphics de respaldo independiente del canvas actual
        Graphics g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();

        Circuit mainCirc = null;
        int totalCells = 0;

        // Asegura un circuito por módulo y guarda el mapping
        Map<String, Circuit> circuitsByModule = new HashMap<>();
        for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) netlist.modules()::iterator) {
            String modName = dto.name();
            circuitsByModule.put(modName, ensureCircuit(proj, modName));
        }

        // Recorre y construye cada módulo en su Circuit correspondiente
        for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) netlist.modules()::iterator) {
            String modName = dto.name();
            Circuit target = circuitsByModule.get(modName);
            if (target == null) {
                // fallback defensivo
                target = ensureCircuit(proj, modName);
                circuitsByModule.put(modName, target);
            }
            if (mainCirc == null) mainCirc = target;

            // Construcción del módulo lógico
            VerilogModuleImpl mod = builder.buildModule(dto);

            System.out.println("== Módulo: " + mod.name() + " ==");
            printModulePorts(mod);

            // Índices auxiliares (layout/memorias)
            ModuleNetIndex netIndex = builder.buildNetIndex(mod);
            printNets(mod, netIndex);

            MemoryIndex memIndex = builder.buildMemoryIndex(mod);
            memoryAdapter.beginModule(memIndex, mod);
            printMemories(memIndex);

            // 1) Alias de celdas de memoria
            Map<VerilogCell, VerilogCell> cellAlias = buildMemoryCellAlias(mod, memIndex);

            // 2) Layout (ELK) con alias
            LayoutBuilder.Result elk = LayoutBuilder.build(proj, mod, netIndex, sizer, cellAlias);
            LayoutRunner.run(elk.root);
            LayoutUtils.applyLayoutAndClamp(elk.root, MIN_X, MIN_Y);

            // 3) Colocar pins top en el circuito del módulo y guardar anchors
            Map<VerilogCell, InstanceHandle> cellHandles = new HashMap<>();
            Map<ModulePort, PortAnchor>      topAnchors  = new HashMap<>();
            addModulePortsToCircuitSeparated(proj, target, mod, elk, g, topAnchors);

            // 4) Instanciar sólo celdas no-aliased en el circuito del módulo
            for (int i = 0; i < mod.cells().size(); i++) {
                VerilogCell cell = mod.cells().get(i);
                if (cellAlias.containsKey(cell)) continue;

                ElkNode n = elk.cellNode.get(cell);
                int x = (n == null) ? snap(MIN_X) : snap((int) Math.round(n.getX()));
                int y = (n == null) ? snap(MIN_Y) : snap((int) Math.round(n.getY()));

                // Instanciación en el circuito objetivo (NO canvas.getCircuit())
                InstanceHandle h = adapter.create(proj, target, g, cell, Location.create(x + SEPARATION_INPUT_CELLS, y + 10));
                cellHandles.put(cell, h);
                totalCells++;
            }

            // 5) Batch por módulo para túneles y constantes en el circuito del módulo
            ImportBatch batch = new ImportBatch(target);

            // 6) Túneles + cables cortos (por puertos y endpoints)
            placePortBasedTunnels(
                    batch, mod,
                    cellHandles, topAnchors, g
            );

            // 7) Constantes (drivers reales) usando PortEndpoint/ModulePort
            placeConstantDriversForModule(
                    batch, proj, mod,
                    cellHandles, topAnchors, g
            );

            // 8) Commit único del módulo
            batch.commit(proj, "addComponentsFromImport");

            System.out.println();
        }

        System.out.println("Total de celdas procesadas: " + totalCells);
        System.out.println("Done.");
        return mainCirc;
    }

    private ModuleMaterializer createFileSystemMaterializer() {
        return new ModuleMaterializer() {

            @Override
            public boolean ensureModule(Project proj, String moduleName) {
                // 1) Si el circuito ya existe, no hacemos nada
                if (findCircuit(proj.getLogisimFile(), moduleName) != null) {
                    System.out.println("[Materializer] Módulo '" + moduleName + "' ya existe en el proyecto.");
                    return true;
                }

                if (baseDir == null) {
                    System.err.println("[Materializer] No se conoce el directorio base para buscar módulos.");
                    return false;
                }

                // 2) Recorre los JSON de la carpeta base buscando el módulo solicitado
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir, "*.json")) {
                    for (Path p : ds) {
                        try {
                            JsonNode node = JsonSynthFile.loadAndValidate(p.toFile());
                            if (node == null) continue;

                            YosysJsonNetlist nl = YosysJsonNetlist.from(node);
                            // Busca si este netlist contiene el módulo deseado
                            boolean has = false;
                            for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) nl.modules()::iterator) {
                                if (moduleName.equals(dto.name())) { has = true; break; }
                            }
                            if (!has) continue;

                            MissingModuleDialog.Choice choice;
                            if (importAllRemaining) {
                                choice = MissingModuleDialog.Choice.IMPORT_THIS;
                            } else {
                                java.awt.Component parent = (proj.getFrame() != null) ? proj.getFrame() : null;
                                choice = MissingModuleDialog.ask(parent, moduleName, p);
                                if (choice == MissingModuleDialog.Choice.IMPORT_ALL) {
                                    importAllRemaining = true;
                                    choice = MissingModuleDialog.Choice.IMPORT_THIS;
                                }
                            }

                            if (choice == MissingModuleDialog.Choice.SKIP_THIS) {
                                System.out.println("[Materializer] Usuario decidió NO importar '" + moduleName + "' de " + p.getFileName());
                                // Sigue buscando otro JSON de la carpeta que pueda contener el mismo módulo
                                continue;
                            }

                            System.out.println("[Materializer] Encontrado módulo '" + moduleName + "' en " + p.getFileName());
                            materializeSingleModule(proj, nl, moduleName);
                            return (findCircuit(proj.getLogisimFile(), moduleName) != null);

                        } catch (Exception e) {
                            System.err.println("[Materializer] Fallo al intentar cargar " + p + ": " + e.getMessage());
                        }
                    }
                } catch (IOException io) {
                    System.err.println("[Materializer] Error leyendo carpeta base: " + io.getMessage());
                }

                System.err.println("[Materializer] No se encontró el módulo '" + moduleName + "' en " + baseDir);
                return false;
            }

            /**
             * Busca un circuito por nombre dentro del LogisimFile actual.
             */
            private Circuit findCircuit(LogisimFile file, String name) {
                for (Circuit c : file.getCircuits()) {
                    if (c.getName().equals(name)) return c;
                }
                return null;
            }

            /**
             * Materializa un módulo específico dentro del proyecto (sin usar chooser).
             * Reutiliza el pipeline de construcción y layout ya existente.
             */
            private void materializeSingleModule(Project proj, YosysJsonNetlist netlist, String moduleName) {
                for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) netlist.modules()::iterator) {
                    if (!moduleName.equals(dto.name())) continue;

                    VerilogModuleImpl mod = builder.buildModule(dto);
                    System.out.println("[Materializer] Materializando submódulo: " + mod.name());

                    // === Build indices ===
                    ModuleNetIndex netIndex = builder.buildNetIndex(mod);
                    MemoryIndex memIndex = builder.buildMemoryIndex(mod);
                    memoryAdapter.beginModule(memIndex, mod);

                    Map<VerilogCell, VerilogCell> cellAlias = buildMemoryCellAlias(mod, memIndex);

                    // === Layout automático con ELK ===
                    LayoutBuilder.Result elk = LayoutBuilder.build(proj, mod, netIndex, sizer, cellAlias);
                    LayoutRunner.run(elk.root);
                    LayoutUtils.applyLayoutAndClamp(elk.root, MIN_X, MIN_Y);

                    // === Obtener/crear el circuito destino con manejo de conflicto de nombre ===
                    Circuit target = ensureCircuit(proj, moduleName);
                    if (target == null) {
                        System.out.println("[Materializer] Usuario canceló materializar '" + moduleName + "'.");
                        return;
                    }
                    if (circuitHasAnyComponent(target)) {
                        System.out.println("[Materializer] Circuito '" + moduleName + "' no está vacío; no se sobrescribe (posible cancel).");
                        return;
                    }

                    Graphics g;
                    try {
                        Canvas canvas = (proj.getFrame() != null) ? proj.getFrame().getCanvas() : null;
                        g = (canvas != null && canvas.getGraphics() != null)
                                ? canvas.getGraphics()
                                : new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();
                    } catch (Throwable t) {
                        g = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB).getGraphics();
                    }

                    // === Añadir puertos top ===
                    Map<VerilogCell, InstanceHandle> cellHandles = new HashMap<>();
                    Map<ModulePort, PortAnchor> topAnchors = new HashMap<>();
                    addModulePortsToCircuitSeparated(proj, target, mod, elk, g, topAnchors);

                    // === Instanciar celdas ===
                    for (VerilogCell cell : mod.cells()) {
                        if (cellAlias.containsKey(cell)) continue;
                        ElkNode n = elk.cellNode.get(cell);
                        int x = (n == null) ? snap(MIN_X) : snap((int) Math.round(n.getX()));
                        int y = (n == null) ? snap(MIN_Y) : snap((int) Math.round(n.getY()));
                        InstanceHandle h = adapter.create(proj, target, g, cell, Location.create(x + SEPARATION_INPUT_CELLS, y));
                        cellHandles.put(cell, h);
                    }

                    // === Batch de adición segura ===
                    ImportBatch batch = new ImportBatch(target);

                    // Túneles
                    placePortBasedTunnels(batch, mod, cellHandles, topAnchors, g);

                    // Constantes
                    placeConstantDriversForModule(batch, proj, mod, cellHandles, topAnchors, g);

                    batch.commit(proj, "materializeModule");

                    System.out.println("[Materializer] Submódulo '" + moduleName + "' agregado correctamente.");
                    return;
                }
            }
        };
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

    /** Crea pins top (inputs a la izquierda, outputs a la derecha) y devuelve anchors (loc+facing). */
    private void addModulePortsToCircuitSeparated(Project proj,
                                                  Circuit circuit,
                                                  VerilogModuleImpl mod,
                                                  LayoutBuilder.Result elk,
                                                  Graphics g,
                                                  Map<ModulePort, PortAnchor> topAnchors) {
        // 1) Caja envolvente de las celdas del módulo
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE;

        for (VerilogCell c : mod.cells()) {
            ElkNode n = elk.cellNode.get(c);
            if (n == null) continue;
            int x0 = (int) Math.round(n.getX());
            int y0 = (int) Math.round(n.getY());
            int x1 = x0 + (int) Math.round(n.getWidth());
            int y1 = y0 + (int) Math.round(n.getHeight());
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

        // 3) Particionar puertos
        List<ModulePort> inputs  = mod.ports().stream()
                .filter(p -> p.direction() == PortDirection.INPUT).toList();
        List<ModulePort> outputs = mod.ports().stream()
                .filter(p -> p.direction() == PortDirection.OUTPUT).toList();

        // 4) Espaciado vertical uniforme para ambos lados
        int spanY = Math.max(1, maxY - minY);

        // ---- Inputs (izquierda, EAST) ----
        int inStep = Math.max(GRID, spanY / Math.max(1, inputs.size() + 1));
        int curInY = minY + inStep;
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

        // ---- Outputs (derecha, WEST) ----
        int outStep = Math.max(GRID, spanY / Math.max(1, outputs.size() + 1));
        int curOutY = minY + outStep;
        for (ModulePort p : outputs) {
            ComponentFactory pinFactory = Pin.FACTORY;
            AttributeSet attrs = pinFactory.createAttributeSet();

            attrs.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, p.width())));
            attrs.setValue(Pin.ATTR_TYPE, true);
            attrs.setValue(Pin.ATTR_TRISTATE, false);
            attrs.setValue(StdAttr.FACING, WEST);
            attrs.setValue(StdAttr.LABEL, p.name());
            attrs.setValue(Pin.ATTR_LABEL_LOC, EAST);

            Location loc = Location.create(snap(xOutputs), snap(curOutY));
            curOutY += outStep;

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

    /* ===================== Túneles con endpoints ===================== */

    /**
     * Punto de conexión (coordenada y orientación)
     * @param loc coordenadas del pin o endpoint
     * @param facing orientación del pin o facingByNearestBorder()
     */
        private record PortAnchor(Location loc, Direction facing) { }

    /** Heurística N/E/S/W según borde más cercano (idéntica a tu versión). */
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
        int min = dxL; facing = Direction.EAST;
        if (dxR < min) { min = dxR; facing = Direction.WEST; }
        if (dyT < min) { min = dyT; facing = Direction.SOUTH; }
        if (dyB < min) { /*min = dyB;*/ facing = Direction.NORTH; }
        return facing;
    }

    /** Túneles por puerto usando PortEndpoint/ModulePort:
     *  - un túnel (multibit) por puerto
     *  - etiqueta N<rango(s)> preservando orden (p. ej. N24-25;13)
     *  - facing por borde más cercano (N/E/S/W)
     *  - siempre un cable corto entre la boca y el túnel (como con constantes)
     */
    private void placePortBasedTunnels(ImportBatch batch,
                                       VerilogModuleImpl mod,
                                       Map<VerilogCell, InstanceHandle> cellHandles,
                                       Map<ModulePort, PortAnchor> topAnchors,
                                       Graphics g) {

        record Key(int x, int y, String label, boolean out) {}
        Set<Key> placed = new HashSet<>();

        // === TOP ports ===
        for (ModulePort p : mod.ports()) {
            PortAnchor anc = topAnchors.get(p);
            if (anc == null) continue;

            // Construir bitSpecs (LSB..MSB)
            List<String> specs = buildBitSpecsForTopPort(mod.name(), p);

            boolean all01 = specs.stream().allMatch(s -> "0".equals(s) || "1".equals(s));
            if (all01) {
                // no crear túnel y dejar que fase de constantes lo materialice
                continue;
            }

            // Modo del túnel:
            // - TOP INPUT → INPUT mode → ATTR_OUTPUT=false
            // - TOP OUTPUT → OUTPUT mode → ATTR_OUTPUT=true
            boolean attrOutput = (p.direction() == PortDirection.OUTPUT);

            Direction facing = (anc.facing == Direction.EAST) ? Direction.WEST : Direction.EAST;

            String pretty = makeLabelForSpecs(specs); // sólo estética
            int step = 10;
            int kx = anc.loc.getX() + (facing == Direction.EAST ?  step : -step);
            int ky = anc.loc.getY();

            Key k = new Key(kx, ky, pretty, attrOutput);
            if (placed.add(k)) {
                createBLTunnelWithWireNear(batch, anc.loc,
                        Math.max(1, p.width()), specs, pretty, facing, attrOutput);
            }
        }

        // === Celdas internas ===
        for (Map.Entry<VerilogCell, InstanceHandle> e : cellHandles.entrySet()) {
            VerilogCell cell = e.getKey();
            InstanceHandle ih = e.getValue();
            if (ih == null || ih.ports == null) continue;

            for (String portName : cell.getPortNames()) {
                int declWidth = Math.max(1, cell.portWidth(portName));

                // Const-only
                PortEndpoint[] byIdx = new PortEndpoint[declWidth];
                for (PortEndpoint ep : cell.endpoints()) {
                    if (!portName.equals(ep.getPortName())) continue;
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < declWidth) byIdx[i] = ep;
                }
                ConstAnalysis ca = analyzeConstBits(byIdx);
                if (ca.allPresent && ca.all01) {
                    continue; // habrá Constant; no túnel
                }

                Location pinLoc = ih.ports.locateByName(portName);
                if (pinLoc == null) continue;

                // Specs por bit
                List<String> specs = buildBitSpecsForCellPort(mod.name(), cell, portName);
                String pretty = makeLabelForSpecs(specs);

                // Dirección de puerto (INPUT/OUTPUT/INOUT) para decidir ATTR_OUTPUT
                Dir d = dirForPort(cell, portName);
                // - Celda OUTPUT → túnel en INPUT → ATTR_OUTPUT=false
                // - Celda INPUT  → túnel en OUTPUT → ATTR_OUTPUT=true
                boolean attrOutput = (d == Dir.IN); // si el puerto de la celda es entrada, el túnel debe salir (OUTPUT mode)

                Direction facing = facingByNearestBorder(ih.component.getBounds(g), pinLoc);

                int step = 10;
                int kx = pinLoc.getX() + (facing == Direction.EAST ?  step :
                        facing == Direction.WEST ? -step : 0);
                int ky = pinLoc.getY() + (facing == Direction.SOUTH ?  step :
                        facing == Direction.NORTH ? -step : 0);

                Key k = new Key(kx, ky, pretty, attrOutput);
                if (placed.add(k)) {
                    createBLTunnelWithWireNear(batch, pinLoc,
                            declWidth, specs, pretty, facing, attrOutput);
                }
            }
        }
    }

    /**
     * Crea un BitLabeledTunnel con un cable corto entre la boca y el túnel.
     * @param batch batch de importación
     * @param pinMouthLoc coordenadas del pin o endpoint
     * @param width anchura en bits del túnel (≥1)
     * @param bitSpecs especificaciones de bits (LSB..MSB), p. ej. ["0","1","N","Z","3-7"]
     * @param prettyLabel etiqueta estética (p. ej. "N24-25;13"), o null/blank para no poner
     * @param facing orientación del túnel (N/E/S/W)
     * @param attrOutput true si el túnel debe estar en modo OUTPUT (sólo salida), false si INPUT (sólo entrada)
     */
    private void createBLTunnelWithWireNear(ImportBatch batch,
                                            Location pinMouthLoc,
                                            int width,
                                            List<String> bitSpecs,  // LSB..MSB
                                            String prettyLabel,     // para StdAttr.LABEL
                                            Direction facing,
                                            boolean attrOutput) {
        final int step = GRID;
        int dx = 0, dy = 0;
        if (facing == Direction.EAST)      dx = -step;
        else if (facing == Direction.WEST) dx =  step;
        else if (facing == Direction.NORTH)dy =  step;
        else if (facing == Direction.SOUTH)dy = -step;

        final Location kLoc = Location.create(pinMouthLoc.getX() + dx, pinMouthLoc.getY() + dy);

        // 1) Wire primero
        batch.add(Wire.create(kLoc, pinMouthLoc));

        // 2) Atributos del BitLabeledTunnel
        AttributeSet a = com.cburch.logisim.std.wiring.BitLabeledTunnel.FACTORY.createAttributeSet();
        a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width)));
        a.setValue(com.cburch.logisim.std.wiring.BitLabeledTunnel.BIT_SPECS, String.join(",", bitSpecs));
        a.setValue(com.cburch.logisim.std.wiring.BitLabeledTunnel.ATTR_OUTPUT, Boolean.valueOf(attrOutput && (facing != Direction.WEST)));
        a.setValue(StdAttr.FACING, facing);
        if (prettyLabel != null && !prettyLabel.isBlank()) {
            a.setValue(StdAttr.LABEL, prettyLabel);
        }

        // 3) Calcular offset para que su pin “caiga” en kLoc
        Component probe = com.cburch.logisim.std.wiring.BitLabeledTunnel.FACTORY.createComponent(Location.create(0, 0), a);
        EndData end0 = probe.getEnd(0);
        int offX = end0.getLocation().getX() - probe.getLocation().getX();
        int offY = end0.getLocation().getY() - probe.getLocation().getY();
        Location tunLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

        // 4) Encolar el componente
        batch.add(com.cburch.logisim.std.wiring.BitLabeledTunnel.FACTORY.createComponent(tunLoc, a));
    }


    /** Dirección lógica de un puerto dentro de un VerilogCell. */
    private enum Dir { IN, OUT, INOUT }

    /** Determina si un puerto actúa como entrada, salida o bidireccional. */
    private static Dir dirForPort(VerilogCell cell, String portName) {
        List<PortEndpoint> eps = cell.endpoints()
                .stream()
                .filter(ep -> portName.equals(ep.getPortName()))
                .toList();

        boolean seenIn = false, seenOut = false;
        for (PortEndpoint ep : eps) {
            switch (ep.getDirection()) {
                case INPUT  -> seenIn = true;
                case OUTPUT -> seenOut = true;
                case INOUT  -> { return Dir.INOUT; }
                default     -> { /* ignore */ }
            }
        }
        if (seenIn && seenOut) return Dir.INOUT;
        if (seenOut) return Dir.OUT;
        return Dir.IN; // por defecto, si solo hay entradas o desconocido
    }

    /* ===================== CONSTANTES: drivers reales ===================== */

    /**
     * Crea componentes Constant y los cablea a los pines cuya conexión es constante.
     * - Para cada puerto de celda/top:
     *   * Si TODOS sus bits son 0/1 → una sola Constant multibit.
     *   * Si mezcla → Constant(1) por cada bit 0/1.
     * - Bits X/Z no generan drivers (no hay fuente tri/unknown aquí).
     */
    private void placeConstantDriversForModule(ImportBatch batch,
                                               Project proj,
                                               VerilogModuleImpl mod,
                                               Map<VerilogCell, InstanceHandle> cellHandles,
                                               Map<ModulePort, PortAnchor> topAnchors,
                                               Graphics g) {
        // ===== 1) Celdas internas =====
        for (VerilogCell cell : mod.cells()) {
            InstanceHandle ih = cellHandles.get(cell);
            if (ih == null || ih.ports == null) continue;

            // Agrupa endpoints por puerto
            Map<String, List<PortEndpoint>> byPort = new LinkedHashMap<>();
            for (PortEndpoint ep : cell.endpoints()) {
                byPort.computeIfAbsent(ep.getPortName(), __ -> new ArrayList<>()).add(ep);
            }

            for (var e : byPort.entrySet()) {
                String pName = e.getKey();
                int width = Math.max(1, cell.portWidth(pName));
                if (width <= 0) continue;

                // Normalizar por índice de bit
                PortEndpoint[] byIdx = new PortEndpoint[width];
                for (PortEndpoint ep : e.getValue()) {
                    int i = ep.getBitIndex();
                    if (i >= 0 && i < width) byIdx[i] = ep;
                }

                // Sólo constante si TODOS son 0/1
                ConstAnalysis ca = analyzeConstBits(byIdx);
                if (!(ca.allPresent && ca.all01)) continue; // NO crear nada para mixtos o nets

                Location pinLoc = ih.ports.locateByName(pName);
                if (pinLoc == null) continue;

                Direction facing = facingByNearestBorder(ih.component.getBounds(g), pinLoc);
                createConstantDriverNear(batch, proj, pinLoc, width, ca.acc, facing);
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
            int acc = 0; // LSB..MSB
            for (int i = 0; i < width; i++) {
                int id = ids[i];
                if (id == ModulePort.CONST_0) {
                    // 0
                } else if (id == ModulePort.CONST_1) {
                    acc |= (1 << i);
                } else {
                    all01 = false; break;
                }
            }

            if (!all01) continue; // NO crear nada cuando hay mezcla o nets

            Direction facing = (anchor.facing == EAST) ? WEST : EAST;
            createConstantDriverNear(batch, proj, anchor.loc, width, acc, facing);
        }
    }


    /** Inserta una constante conectada a la "boca" loc. Crea primero el wire y luego la constante, en BATCH. */
    private void createConstantDriverNear(ImportBatch batch,
                                          Project proj,
                                          Location loc,        // boca del pin/bus destino
                                          int width,           // >=1
                                          int value,           // LSB=bit0
                                          Direction facing) {
        try {
            final int step = GRID;

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
            ComponentFactory constF = Constant.FACTORY;
            if (constF == null) return;

            AttributeSet a = constF.createAttributeSet();
            try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) { }
            try { a.setValue(StdAttr.FACING, facing); } catch (Exception ignore) { }

            // Valor (usa helper tolerante)
            boolean setOk = false;
            try {
                setConstantValueFlexible(a, width, value);
                setOk = true;
            } catch (Throwable ignore) {
            }
            if (!setOk) {
                String hex = "0x" + Integer.toHexString(value);
                setParsedByName(a, "value", hex);
            }

            // 4) Alinea la Constant para que su pin caiga EXACTO en kLoc
            Component probe = constF.createComponent(Location.create(0, 0), a);
            EndData outEnd = probe.getEnd(0); // Constant tiene 1 pin
            int offX = outEnd.getLocation().getX() - probe.getLocation().getX();
            int offY = outEnd.getLocation().getY() - probe.getLocation().getY();
            Location constLoc = Location.create(kLoc.getX() - offX, kLoc.getY() - offY);

            // 4) Encolar wire primero (kLoc -> loc)
            batch.add(Wire.create(kLoc, loc));

            // 5) Encolar la constante (su pin cae en kLoc y queda conectado al wire)
            Component k = constF.createComponent(constLoc, a);
            batch.add(k);

        } catch (Exception ignore) {
            // Silencioso para no abortar import completo
        }
    }

    // Devuelve CSV LSB..MSB con "0","1","x" o "N<id>"
    private List<String> buildBitSpecsForCellPort(String moduleName, VerilogCell cell, String portName) {
        int w = Math.max(1, cell.portWidth(portName));
        PortEndpoint[] byIdx = new PortEndpoint[w];
        for (PortEndpoint ep : cell.endpoints()) {
            if (!portName.equals(ep.getPortName())) continue;
            int i = ep.getBitIndex();
            if (i >= 0 && i < w) byIdx[i] = ep;
        }
        List<String> specs = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            PortEndpoint ep = byIdx[i];
            if (ep == null) {
                // Falta endpoint → considéralo X y registra advertencia
                specs.add("x");
                xWarnings.addXBit(moduleName + " :: " + cell.name(), portName, i, "bit sin endpoint (indeterminado)");
                continue;
            }
            BitRef br = ep.getBitRef();
            if (br instanceof Const0) { specs.add("0"); continue; }

            String n = (br == null) ? "" : br.getClass().getSimpleName();
            if ("Const1".equals(n)) { specs.add("1"); continue; }
            if ("ConstX".equals(n) || "ConstZ".equals(n)) {
                specs.add("x");
                xWarnings.addXBit(moduleName + " :: " + cell.name(), portName, i, "endpoint constante X/Z");
                continue;
            }

            // Net
            Integer nid = ep.getNetIdOrNull();
            if (nid == null) {
                specs.add("x");
                xWarnings.addXBit(moduleName + " :: " + cell.name(), portName, i, "endpoint sin netId (indeterminado)");
            } else {
                specs.add("N" + nid);
            }
        }
        return specs;
    }

    // Para puertos TOP usando netIds() (enteros o constantes especiales)
    private List<String> buildBitSpecsForTopPort(String moduleName, ModulePort p) {
        int w = Math.max(1, p.width());
        int[] arr = p.netIds();
        List<String> specs = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            int nid = (arr != null && i < arr.length) ? arr[i] : ModulePort.CONST_X;
            switch (nid) {
                case ModulePort.CONST_0 -> specs.add("0");
                case ModulePort.CONST_1 -> specs.add("1");
                default -> {
                    // cualquier "no 0/1" podría ser net, X o Z
                    if (nid >= 0) {
                        specs.add("N" + nid);
                    } else {
                        specs.add("x");
                        xWarnings.addXBit(moduleName, p.name(), i, "top-port con net no resuelta/indeterminada");
                    }
                }
            }
        }
        return specs;
    }

    // Compacta etiqueta visible tipo N4-6;9 (no se usa en lógica, sólo estética)
    private static String makeLabelForSpecs(List<String> specs) {
        List<Integer> netsOrdered = new ArrayList<>();
        for (String s : specs) if (s.startsWith("N")) {
            try { netsOrdered.add(Integer.parseInt(s.substring(1))); } catch (Exception ignore) {}
        }
        if (netsOrdered.isEmpty()) return ""; // todo constantes → sin label visible
        List<int[]> ranges = contiguousRanges(netsOrdered);
        StringBuilder sb = new StringBuilder("N");
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (i > 0) sb.append(';');
            if (r[0] == r[1]) sb.append(r[0]);
            else sb.append(r[0]).append('-').append(r[1]);
        }
        return sb.toString();
    }

    private static List<int[]> contiguousRanges(List<Integer> ordered) {
        List<int[]> out = new ArrayList<>();
        if (ordered.isEmpty()) return out;
        int start = ordered.get(0), prev = start;
        for (int i = 1; i < ordered.size(); i++) {
            int cur = ordered.get(i);
            if (cur == prev + 1) prev = cur; else {
                out.add(new int[]{start, prev});
                start = prev = cur;
            }
        }
        out.add(new int[]{start, prev});
        return out;
    }

    /**
     * Resultado del análisis de bits de un puerto.
     * @param allPresent true si todos los bits del puerto están conectados a algo
     * @param all01 true si todos los bits son 0/1 (no hay X/Z/Net)
     * @param acc LSB..MSB (solo válido si all01 == true)
     */
    private record ConstAnalysis(boolean allPresent, boolean all01, int acc) { }

    private static ConstAnalysis analyzeConstBits(PortEndpoint[] byIdx) {
        boolean allPresent = true, all01 = true;
        int acc = 0;
        for (int i = 0; i < byIdx.length; i++) {
            PortEndpoint ep = byIdx[i];
            if (ep == null) { allPresent = false; all01 = false; break; }
            BitRef br = ep.getBitRef();
            if (br instanceof Const0) {
                // 0 -> no set
            } else {
                String n = (br == null) ? "" : br.getClass().getSimpleName();
                if ("Const1".equals(n)) {
                    acc |= (1 << i);
                } else if ("ConstX".equals(n) || "ConstZ".equals(n)) {
                    all01 = false;
                } else {
                    // Net → no es 0/1
                    all01 = false;
                }
            }
        }
        return new ConstAnalysis(allPresent, all01, acc);
    }
}
