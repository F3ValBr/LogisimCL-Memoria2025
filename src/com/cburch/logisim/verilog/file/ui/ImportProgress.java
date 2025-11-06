package com.cburch.logisim.verilog.file.ui;

public interface ImportProgress {

    /**
     * Se llama una sola vez al comenzar toda la importación.
     * Ej.: "Analizando netlist…"
     */
    void onStart(String message);

    /**
     * Se llama varias veces durante la importación para contar en qué fase va.
     * Ej.: "Importando módulo alu", "Colocando celdas…", etc.
     */
    void onPhase(String message);

    /**
     * Se llama al terminar correctamente toda la importación.
     */
    void onDone();

    /**
     * Se llama cuando algo falla.
     */
    void onError(String message, Throwable cause);
}
