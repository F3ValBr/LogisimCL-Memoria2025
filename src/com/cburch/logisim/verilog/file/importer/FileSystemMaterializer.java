package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.data.*;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.file.JsonSynthFile;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysJsonNetlist;
import com.cburch.logisim.verilog.file.jsonhdlr.YosysModuleDTO;
import com.cburch.logisim.verilog.file.materializer.ModuleMaterializer;
import com.cburch.logisim.verilog.file.ui.MissingModuleDialog;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

final class FileSystemMaterializer implements ModuleMaterializer {

    interface BaseDirSupplier extends java.util.function.Supplier<Path> {}
    interface PipelineMaterialize { void materialize(Project proj, YosysJsonNetlist nl, String name); }
    interface FlagSupplier extends java.util.function.BooleanSupplier {}
    interface FlagConsumer extends java.util.function.Consumer<Boolean> {}

    private final BaseDirSupplier baseDir;
    private final PipelineMaterialize materializeFn;
    private final FlagSupplier importAll;
    private final FlagConsumer setImportAll;

    /** Creates a new file system materializer.
     * @param baseDir Supplies the base directory where to look for files.
     * @param materializeFn Function that materializes a module from a netlist.
     * @param importAll Supplies whether to import all missing modules without asking.
     * @param setImportAll Consumer to set the "import all" flag.
     */
    FileSystemMaterializer(BaseDirSupplier baseDir,
                           PipelineMaterialize materializeFn,
                           FlagSupplier importAll,
                           FlagConsumer setImportAll) {
        this.baseDir = baseDir;
        this.materializeFn = materializeFn;
        this.importAll = importAll;
        this.setImportAll = setImportAll;
    }

    @Override
    public boolean ensureModule(Project proj, String moduleName) {
        if (findCircuit(proj.getLogisimFile(), moduleName) != null) return true;

        Path dir = baseDir.get();
        if (dir == null) return false;

        try (DirectoryStream<Path> ds = Files.newDirectoryStream(dir, "*.json")) {
            for (Path p : ds) {
                try {
                    JsonNode node = JsonSynthFile.loadAndValidate(p.toFile());
                    if (node == null) continue;

                    YosysJsonNetlist nl = YosysJsonNetlist.from(node);
                    boolean has = false;
                    for (YosysModuleDTO dto : (Iterable<YosysModuleDTO>) nl.modules()::iterator) {
                        if (moduleName.equals(dto.name())) { has = true; break; }
                    }
                    if (!has) continue;

                    MissingModuleDialog.Choice choice;
                    if (importAll.getAsBoolean()) {
                        choice = MissingModuleDialog.Choice.IMPORT_THIS;
                    } else {
                        java.awt.Component parent = (proj.getFrame() != null) ? proj.getFrame() : null;
                        choice = MissingModuleDialog.ask(parent, moduleName, p);
                        if (choice == MissingModuleDialog.Choice.IMPORT_ALL) {
                            setImportAll.accept(true);
                            choice = MissingModuleDialog.Choice.IMPORT_THIS;
                        }
                    }

                    if (choice == MissingModuleDialog.Choice.SKIP_THIS) continue;

                    materializeFn.materialize(proj, nl, moduleName);
                    return (findCircuit(proj.getLogisimFile(), moduleName) != null);

                } catch (Exception ignore) { }
            }
        } catch (IOException ignore) { }

        return false;
    }

    private static Circuit findCircuit(LogisimFile file, String name) {
        for (Circuit c : file.getCircuits()) if (c.getName().equals(name)) return c;
        return null;
    }
}

