package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.verilog.file.Strings;

import javax.swing.JOptionPane;
import java.awt.Component;

public final class ImportCompletionDialog {

    private ImportCompletionDialog() {}

    public enum Choice { GO_TO_MODULE, STAY_HERE }

    public static Choice show(Component parent, String moduleName) {
        String title = Strings.get("import.done.title");
        String msg   = Strings.get("import.done.message", moduleName);

        Object[] options = {
                Strings.get("import.done.goto"),
                Strings.get("import.done.stay")
        };

        int sel = JOptionPane.showOptionDialog(
                parent,
                msg,
                title,
                JOptionPane.YES_NO_OPTION,
                JOptionPane.INFORMATION_MESSAGE,
                null,
                options,
                options[0]
        );
        return (sel == JOptionPane.YES_OPTION) ? Choice.GO_TO_MODULE : Choice.STAY_HERE;
    }
}

