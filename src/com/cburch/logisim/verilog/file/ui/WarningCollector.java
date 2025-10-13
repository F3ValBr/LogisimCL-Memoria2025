package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.proj.Project;

import javax.swing.*;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public final class WarningCollector {
    private final List<String> lines = new ArrayList<>();
    private final Set<String> keys   = new HashSet<>();

    /** Limpia todos los avisos (úsalo al inicio de cada importación). */
    public void clear() {
        lines.clear();
        keys.clear();
    }

    /** Agrega un aviso por bit X/Z, evitando duplicados exactos. */
    public void addXBit(String scope, String port, int bitIndex, String note) {
        String normScope = (scope == null) ? "" : scope.trim();
        String normPort  = (port  == null) ? "" : port.trim();
        String normNote  = (note  == null) ? "" : note.trim();

        String key = normScope + "|" + normPort + "|" + bitIndex + "|" + normNote;
        if (!keys.add(key)) return; // ya registrado

        String msg = String.format(" • %s :: puerto %s, bit %d → X%s",
                normScope, normPort, bitIndex,
                normNote.isEmpty() ? "" : " (" + normNote + ")");
        lines.add(msg);
    }

    /** Agrega un aviso genérico, evitando duplicados exactos. */
    public void addGeneric(String text) {
        if (text == null) return;
        String norm = text.trim();
        if (norm.isEmpty()) return;

        String msg = " • " + norm;
        if (keys.add("GEN|" + norm)) {
            lines.add(msg);
        }
    }

    public boolean hasWarnings() { return !lines.isEmpty(); }

    public String summary() {
        int n = lines.size();
        if (n == 0) return "";
        return (n == 1)
                ? "Se detectó 1 bit indeterminado (X) durante la importación."
                : "Se detectaron " + n + " bits indeterminados (X) durante la importación.";
    }

    public String details() {
        StringBuilder sb = new StringBuilder();
        sb.append("ADVERTENCIAS DE IMPORTACIÓN (bits X detectados)\n\n");
        for (String s : lines) sb.append(s).append('\n');
        sb.append("\nCausa típica: conexiones no resueltas, Z/alta impedancia, o constantes 'x' en el JSON.\n");
        return sb.toString();
    }

    public static void showDetailsDialog(java.awt.Component parent, String title, String text) {
        JTextArea area = new JTextArea(text, 22, 80);
        area.setEditable(false);
        area.setFont(new java.awt.Font("monospaced", java.awt.Font.PLAIN, 12));
        JScrollPane sp = new JScrollPane(area);
        JOptionPane.showMessageDialog(
                parent,
                sp,
                title,
                JOptionPane.WARNING_MESSAGE
        );
    }

    /** Muestra en el EDT una advertencia con los bits X detectados tras la importación. */
    public static void showXWarningDialogLater(Project proj, WarningCollector warnings) {
        SwingUtilities.invokeLater(() -> {
            java.awt.Component parent = (proj.getFrame() != null) ? proj.getFrame() : null;

            String summary = warnings.summary() + "\n\n" +
                    "Esto puede producir salidas 'desconocidas' en simulación.\n" +
                    "Causa típica: bits sin fuente, alta impedancia (Z) o 'x' en el JSON.";

            String[] options = { "Ver detalles…", "OK" };
            int sel = JOptionPane.showOptionDialog(
                    parent,
                    summary,
                    "Advertencia: bits X detectados",
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]
            );

            if (sel == 0) {
                showDetailsDialog(parent, "Detalles de bits X", warnings.details());
            }
        });
    }
}
