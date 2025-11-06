package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.file.Strings;


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
        if (!keys.add(key)) return;

        // " • {0} :: puerto {1}, bit {2} → X{3}"
        String extra = normNote.isEmpty() ? "" : " (" + normNote + ")";
        String msg = Strings.get("import.warn.xbit.line",
                normScope, normPort, String.valueOf(bitIndex), extra);
        lines.add(msg);
    }

    /** Agrega un aviso genérico, evitando duplicados exactos. */
    public void addGeneric(String text) {
        if (text == null) return;
        String norm = text.trim();
        if (norm.isEmpty()) return;

        if (keys.add("GEN|" + norm)) {
            lines.add(" • " + norm);
        }
    }

    public boolean hasWarnings() { return !lines.isEmpty(); }

    public String summary() {
        int n = lines.size();
        if (n == 0) return "";
        if (n == 1) {
            return Strings.get("import.warn.xbit.summary.one");
        }
        // "Se detectaron {0} bits..."
        return Strings.get("import.warn.xbit.summary.many", n);
    }

    public String details() {
        StringBuilder sb = new StringBuilder();
        sb.append(Strings.get("import.warn.xbit.details.title")).append("\n\n");
        for (String s : lines) sb.append(s).append('\n');
        sb.append(Strings.get("import.warn.xbit.details.footer"));
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

            String summary = warnings.summary();
            if (summary == null || summary.isBlank()) return;

            String body = summary + "\n\n" + Strings.get("import.warn.xbit.dialog.body");

            String[] options = {
                    Strings.get("import.warn.xbit.dialog.btn.details"),
                    Strings.get("import.warn.xbit.dialog.btn.ok")
            };

            int sel = JOptionPane.showOptionDialog(
                    parent,
                    body,
                    Strings.get("import.warn.xbit.dialog.title"),
                    JOptionPane.DEFAULT_OPTION,
                    JOptionPane.WARNING_MESSAGE,
                    null, options, options[1]
            );

            if (sel == 0) {
                showDetailsDialog(
                        parent,
                        Strings.get("import.warn.xbit.details.window.title"),
                        warnings.details()
                );
            }
        });
    }
}

