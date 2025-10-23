package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.arith.ArithmeticPortMapRegister;
import com.cburch.logisim.std.gates.GatesPortMapRegister;
import com.cburch.logisim.std.memory.MemoryPortMapRegister;
import com.cburch.logisim.std.plexers.PlexersPortMapRegister;
import com.cburch.logisim.std.yosys.YosysComponentsPortMapRegister;
import com.cburch.logisim.verilog.comp.CellFactoryRegistry;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleBuilder;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysJsonNetlist;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.file.ui.ImportCompletionDialog;
import com.cburch.logisim.verilog.file.ui.NavigationHelper;
import com.cburch.logisim.verilog.file.ui.WarningCollector;
import com.cburch.logisim.verilog.layout.auxiliary.DefaultNodeSizer;
import com.cburch.logisim.verilog.layout.auxiliary.NodeSizer;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.ComponentAdapterRegistry;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.awt.*;
import java.nio.file.Path;
import java.util.*;
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

        // Show file chooser and load JSON
        var res = proj.getLogisimFile().getLoader().JSONImportChooserWithPath(proj.getFrame());
        if (res == null || res.root() == null || res.path() == null) return;

        this.baseDir = res.path().getParent();

        // Parse JSON netlist
        YosysJsonNetlist netlist = YosysJsonNetlist.from(res.root());

        // Run import pipeline
        ImportPipeline pipeline = new ImportPipeline(
                proj, registry, builder, memoryAdapter, adapter, sizer,
                xWarnings,
                new LayoutServices(MIN_X, MIN_Y, GRID, PAD_X, SEPARATION_INPUT_CELLS),
                new TunnelPlacer(GRID),
                new ConstantPlacer(GRID),
                new SpecBuilder(xWarnings)
        );

        // Import main module and related modules
        Circuit main = pipeline.run(netlist);

        // Show missing modules dialog if needed
        if (main != null) {
            var choice = ImportCompletionDialog.show(proj.getFrame(), main.getName());
            if (choice == ImportCompletionDialog.Choice.GO_TO_MODULE) {
                if (!NavigationHelper.switchToCircuit(proj, main)) {
                    NavigationHelper.showManualSwitchHint(proj, main);
                }
            }
        }
        // Show warnings if any
        if (xWarnings.hasWarnings()) {
            showXWarningDialogLater(proj, xWarnings);
        }

        netlist = null;
    }

    /* ===== Materializer ===== */

    /** Creates a file system based module materializer.
     * This materializer loads modules from files in the base directory.
     * @return A new ModuleMaterializer instance.
     */
    private ModuleMaterializer createFileSystemMaterializer() {
        return new FileSystemMaterializer(
                () -> baseDir,
                (proj, nl, name) -> new ImportPipeline(
                        proj, registry, builder, memoryAdapter, adapter, sizer,
                        xWarnings,
                        new LayoutServices(MIN_X, MIN_Y, GRID, PAD_X, SEPARATION_INPUT_CELLS),
                        new TunnelPlacer(GRID),
                        new ConstantPlacer(GRID),
                        new SpecBuilder(xWarnings)
                ).materializeSingleModule(nl, name),
                () -> importAllRemaining,
                v -> importAllRemaining = v
        );
    }
}
