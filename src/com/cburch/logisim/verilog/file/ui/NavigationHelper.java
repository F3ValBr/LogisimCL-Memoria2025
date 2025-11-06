package com.cburch.logisim.verilog.file.ui;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.gui.main.Frame;
import com.cburch.logisim.proj.Action;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.file.Strings;


import javax.swing.*;
import java.lang.reflect.Method;

public final class NavigationHelper {
    private NavigationHelper() {}

    /** Intenta cambiar el “circuito actual” usando las APIs disponibles en tu build. */
    public static boolean switchToCircuit(Project proj, Circuit target) {
        if (proj == null || target == null) return false;

        // 1) Project.setCurrentCircuit(Circuit)
        try {
            Method m = Project.class.getMethod("setCurrentCircuit", Circuit.class);
            m.invoke(proj, target);
            return true;
        } catch (Throwable ignore) {}

        // 2) Frame.setCurrentCircuit(Circuit) o Frame.setCircuit(Circuit)
        try {
            Frame frame = proj.getFrame();
            if (frame != null) {
                try {
                    Method m = frame.getClass().getMethod("setCurrentCircuit", Circuit.class);
                    m.invoke(frame, target);
                    return true;
                } catch (Throwable ignore2) { }
                try {
                    Method m = frame.getClass().getMethod("setCircuit", Circuit.class);
                    m.invoke(frame, target);
                    return true;
                } catch (Throwable ignore3) { }
            }
        } catch (Throwable ignore) {}

        // 3) Canvas.setCircuit(Circuit)
        try {
            Frame frame = proj.getFrame();
            if (frame != null) {
                Canvas canvas = frame.getCanvas();
                if (canvas != null) {
                    Method m = canvas.getClass().getMethod("setCircuit", Circuit.class);
                    m.invoke(canvas, target);
                    return true;
                }
            }
        } catch (Throwable ignore) {}

        // 4) Acción del archivo: LogisimFileActions.setCurrentCircuit(Circuit) (si existe)
        try {
            Method m = LogisimFileActions.class.getMethod("setCurrentCircuit", Circuit.class);
            Object action = m.invoke(null, target);
            // La clase Action exacta puede variar; Project.doAction suele aceptar el tipo correcto
            proj.doAction((Action) action);
            return true;
        } catch (Throwable ignore) {}

        return false;
    }

    /** Si no se pudo navegar, muestra un aviso amable. */
    public static void showManualSwitchHint(Project proj, Circuit target) {
        try {
            JOptionPane.showMessageDialog(
                    proj != null ? proj.getFrame() : null,
                    Strings.get("import.nav.cannot.switch", target.getName()),
                    Strings.get("import.nav.cannot.switch.title"),
                    JOptionPane.INFORMATION_MESSAGE
            );
        } catch (Throwable ignore) {}
    }
}

