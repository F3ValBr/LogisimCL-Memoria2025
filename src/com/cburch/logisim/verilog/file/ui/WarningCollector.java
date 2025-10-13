package com.cburch.logisim.verilog.file.ui;

import java.util.ArrayList;
import java.util.List;

public final class WarningCollector {
    private final List<String> lines = new ArrayList<>();

    public void addXBit(String scope, String port, int bitIndex, String note) {
        String msg = String.format(" • %s :: puerto %s, bit %d → X%s",
                scope, port, bitIndex, (note == null || note.isBlank()) ? "" : " (" + note + ")");
        lines.add(msg);
    }

    public void addGeneric(String text) {
        if (text != null && !text.isBlank()) lines.add(" • " + text);
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
        javax.swing.JTextArea area = new javax.swing.JTextArea(text, 22, 80);
        area.setEditable(false);
        area.setFont(new java.awt.Font("monospaced", java.awt.Font.PLAIN, 12));
        javax.swing.JScrollPane sp = new javax.swing.JScrollPane(area);
        javax.swing.JOptionPane.showMessageDialog(
                parent,
                sp,
                title,
                javax.swing.JOptionPane.WARNING_MESSAGE
        );
    }

}

