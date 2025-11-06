package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.verilog.file.Strings;

import javax.swing.*;
import java.awt.*;

public final class ImportProgressDialog extends JDialog implements ImportProgress {

    private final JLabel label = new JLabel(Strings.get("import.progress.default"));
    private final JProgressBar bar = new JProgressBar();

    public ImportProgressDialog(JFrame owner) {
        super(owner, Strings.get("import.progress.title"), true);
        setLayout(new BorderLayout(8, 8));

        add(label, BorderLayout.NORTH);

        bar.setIndeterminate(true);
        bar.setStringPainted(false);
        add(bar, BorderLayout.CENTER);

        setSize(360, 120);
        setLocationRelativeTo(owner);
    }

    @Override
    public void onStart(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (msg != null && !msg.isBlank()) {
                label.setText(msg);
            } else {
                label.setText(Strings.get("import.progress.default"));
            }
            bar.setIndeterminate(true);
        });
    }

    @Override
    public void onPhase(String msg) {
        SwingUtilities.invokeLater(() -> {
            if (msg != null && !msg.isBlank()) {
                label.setText(msg);
            }
            bar.setIndeterminate(true);
        });
    }

    @Override
    public void onDone() {
        SwingUtilities.invokeLater(() -> {
            setVisible(false);
            dispose();
        });
    }

    @Override
    public void onError(String msg, Throwable cause) {
        SwingUtilities.invokeLater(() -> {
            if (msg != null && !msg.isBlank()) {
                label.setText(Strings.get("import.progress.error") + ": " + msg);
            } else {
                label.setText(Strings.get("import.progress.error"));
            }
            bar.setIndeterminate(false);
            bar.setValue(0);
            setVisible(false);
            dispose();
        });
        if (cause != null) {
            cause.printStackTrace();
        }
    }
}
