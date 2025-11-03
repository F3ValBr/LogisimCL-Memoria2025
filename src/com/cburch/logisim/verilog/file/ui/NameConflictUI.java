package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.file.Strings;

import javax.swing.*;

// ===== 3) Capa de UI (separada) =====
public final class NameConflictUI {

    public record NameConflictResult(Choice choice, String suggestedName) { }

    public enum Choice { REPLACE, CREATE_NEW, CANCEL }

    public static NameConflictResult askUser(Project proj, String baseName) {
        java.awt.Component parent = (proj != null && proj.getFrame() != null) ? proj.getFrame() : null;

        String msg = Strings.get("import.nameconflict.message", baseName);
        String[] options = {
                Strings.get("import.nameconflict.replace"),
                Strings.get("import.nameconflict.create_new"),
                Strings.get("import.nameconflict.cancel")
        };

        int sel = JOptionPane.showOptionDialog(
                parent,
                msg,
                Strings.get("import.nameconflict.title"),
                JOptionPane.DEFAULT_OPTION,
                JOptionPane.QUESTION_MESSAGE,
                null,
                options,
                options[0]
        );

        return switch (sel) {
            case 0 -> new NameConflictResult(Choice.REPLACE, null);
            case 1 -> {
                String suggested = baseName + "_new";
                yield new NameConflictResult(Choice.CREATE_NEW, suggested);
            }
            default -> new NameConflictResult(Choice.CANCEL, null);
        };
    }
}
