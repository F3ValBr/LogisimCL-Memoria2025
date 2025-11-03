package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.arith.ArithmeticPortMapRegister;
import com.cburch.logisim.std.gates.GatesPortMapRegister;
import com.cburch.logisim.std.memory.MemoryPortMapRegister;
import com.cburch.logisim.std.plexers.PlexersPortMapRegister;
import com.cburch.logisim.std.yosys.YosysComponentsPortMapRegister;
import com.cburch.logisim.verilog.comp.CellFactoryRegistry;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleBuilder;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysJsonNetlist;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.file.ui.*;
import com.cburch.logisim.verilog.layout.auxiliary.DefaultNodeSizer;
import com.cburch.logisim.verilog.layout.auxiliary.NodeSizer;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.ComponentAdapterRegistry;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;
import com.cburch.logisim.verilog.std.adapters.gatelvl.RegisterGateOpAdapter;
import com.cburch.logisim.verilog.std.adapters.gatelvl.GateOpAdapter;
import com.cburch.logisim.verilog.std.adapters.ips.IPOpAdapter;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;
import com.fasterxml.jackson.databind.JsonNode;

import javax.swing.*;
import java.nio.file.Path;
import java.util.List;

import static com.cburch.logisim.verilog.file.ui.WarningCollector.showXWarningDialogLater;

public final class VerilogJsonImporter {

    // Variables comunes para el layout
    public static final int GRID  = 10;
    public static final int MIN_X = 80;
    public static final int MIN_Y = 80;
    static final int SEPARATION_INPUT_CELLS = 150;
    static final int PAD_X = SEPARATION_INPUT_CELLS + 100;

    private final CellFactoryRegistry registry;
    private final VerilogModuleBuilder builder;
    private final MemoryOpAdapter memoryAdapter = new MemoryOpAdapter();
    private final ComponentAdapterRegistry adapter;
    private final NodeSizer sizer;
    private final WarningCollector xWarnings = new WarningCollector();

    private Path baseDir;
    private volatile boolean importAllRemaining = false;

    /**
     * Creates a new Verilog JSON importer.
     * @param registry Cell factory registry to use for creating components.
     */
    public VerilogJsonImporter(CellFactoryRegistry registry) {
        this.registry = registry;
        this.builder = new VerilogModuleBuilder(registry);
        this.adapter = new ComponentAdapterRegistry()
                .register(new IPOpAdapter())
                .register(new GateOpAdapter())
                .register(new RegisterGateOpAdapter())
                .register(new UnaryOpAdapter())
                .register(new BinaryOpAdapter())
                .register(new MuxOpAdapter())
                .register(new RegisterOpAdapter())
                .register(memoryAdapter)
                .register(new ModuleBlackBoxAdapter(createFileSystemMaterializer()));
        this.sizer = new DefaultNodeSizer(adapter);
    }

    /**
     * Imports a Verilog JSON netlist into the given project.
     * @param proj The project to import into.
     */
    public void importInto(Project proj) {
        // 1. init port maps
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

        var chooserRes = proj.getLogisimFile()
                .getLoader()
                .JSONImportChooserWithPath(proj.getFrame());
        if (chooserRes == null || chooserRes.root() == null || chooserRes.path() == null)
            return;

        final JsonNode rootJson = chooserRes.root();
        final java.nio.file.Path basePath  = chooserRes.path();
        this.baseDir = basePath.getParent();

        final JFrame owner = proj.getFrame();
        // dialog handler
        final ImportProgressDialog dlg = new ImportProgressDialog(owner);

        SwingWorker<Circuit, Void> worker = new SwingWorker<>() {
            @Override
            protected Circuit doInBackground() throws Exception {
                // parse JSON netlist
                dlg.onStart("Analizando netlist…");
                YosysJsonNetlist netlist = YosysJsonNetlist.from(rootJson);

                // run import pipeline
                dlg.onPhase("Preparando importación…");
                ImportPipeline pipeline = new ImportPipeline(
                        proj,
                        registry,
                        builder,
                        memoryAdapter,
                        adapter,
                        sizer,
                        xWarnings,
                        new LayoutServices(MIN_X, MIN_Y, GRID, PAD_X, SEPARATION_INPUT_CELLS),
                        new TunnelPlacer(GRID),
                        new ConstantPlacer(GRID),
                        new SpecBuilder(xWarnings),
                        dlg
                );

                // import main module and related modules
                dlg.onPhase("Importando módulos…");
                Circuit main = pipeline.run(netlist);

                dlg.onDone();
                return main;
            }

            @Override
            protected void done() {
                if (dlg.isShowing()) {
                    dlg.setVisible(false);
                    dlg.dispose();
                }
                Circuit main = null;
                try {
                    main = get();
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(
                            owner,
                            "No se pudo importar: " + ex.getMessage(),
                            "Error al importar",
                            JOptionPane.ERROR_MESSAGE
                    );
                    ex.printStackTrace();
                }

                if (main != null) {
                    var choice = ImportCompletionDialog.show(owner, main.getName());
                    if (choice == ImportCompletionDialog.Choice.GO_TO_MODULE) {
                        if (!NavigationHelper.switchToCircuit(proj, main)) {
                            NavigationHelper.showManualSwitchHint(proj, main);
                        }
                    }
                }

                if (xWarnings.hasWarnings()) {
                    showXWarningDialogLater(proj, xWarnings);
                }
            }
        };

        worker.execute();
        dlg.setVisible(true);
    }

    /* ===== Materializer ===== */

    /** Creates a file system based module materializer.
     * This materializer loads modules from files in the base directory.
     * @return A new ModuleMaterializer instance.
     */
    private ModuleMaterializer createFileSystemMaterializer() {
        return new FileSystemMaterializer(
                () -> baseDir,
                (proj, nl, name) -> {
                    ImportProgress silent = new ImportProgress() {
                        @Override public void onStart(String msg) { /* no-op */ }
                        @Override public void onPhase(String msg) { /* no-op */ }
                        @Override public void onDone() { /* no-op */ }
                        @Override public void onError(String msg, Throwable cause) { cause.printStackTrace(); }
                    };

                    new ImportPipeline(
                            proj,
                            registry,
                            builder,
                            memoryAdapter,
                            adapter,
                            sizer,
                            xWarnings,
                            new LayoutServices(MIN_X, MIN_Y, GRID, PAD_X, SEPARATION_INPUT_CELLS),
                            new TunnelPlacer(GRID),
                            new ConstantPlacer(GRID),
                            new SpecBuilder(xWarnings),
                            silent
                    ).materializeSingleModule(nl, name);
                },
                () -> importAllRemaining,
                v -> importAllRemaining = v
        );
    }
}
