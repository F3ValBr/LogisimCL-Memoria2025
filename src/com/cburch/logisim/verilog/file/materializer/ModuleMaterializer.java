package com.cburch.logisim.verilog.file.materializer;

import com.cburch.logisim.proj.Project;

public interface ModuleMaterializer {
    /** Asegura que exista un Circuit con el nombre dado (lo construye si es necesario). */
    boolean ensureModule(Project proj, String moduleName);

    static ModuleMaterializer noop() {
        return (proj, moduleName) -> false;
    }
}

