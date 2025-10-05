package com.cburch.logisim.verilog.std.adapters;


import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.Component;

import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.file.LogisimFileActions;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Dependencies;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.std.*;

import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ModuleBlackBoxAdapter extends AbstractComponentAdapter {

    @Override public boolean accepts(CellType t) { return true; } // fallback universal

    @Override
    public InstanceHandle create(Canvas canvas, Graphics gMaybeNull, VerilogCell cell, Location where) {
        try {
            Project proj = canvas.getProject();
            Circuit currentCirc = canvas.getCircuit();

            // Graphics de respaldo si viene null
            Graphics g = gMaybeNull;
            if (g == null) {
                var img = new BufferedImage(1,1, java.awt.image.BufferedImage.TYPE_INT_ARGB);
                g = img.getGraphics();
            }

            String modName = safeName(cell.type().typeId());

            Circuit newCirc = findCircuit(proj.getLogisimFile(), modName);
            if (newCirc == null) {
                newCirc = new Circuit(modName);
                proj.doAction(LogisimFileActions.addCircuit(newCirc));
            }

            if (newCirc == currentCirc) {
                canvas.setErrorMessage(Strings.getter("circularError"));
                return null;
            }

            Dependencies depends = proj.getDependencies();

            if (!depends.canAdd(currentCirc, newCirc)) {
                canvas.setErrorMessage(Strings.getter("circularError"));
                return null;
            }

            InstanceFactory factory = newCirc.getSubcircuitFactory();
            AttributeSet attrs = factory.createAttributeSet();

            attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name()));
            attrs.setValue(CircuitAttributes.LABEL_LOCATION_ATTR, Direction.NORTH);

            // 4) Añadir con acción (undo/redo)
            Component comp = addComponent(proj, currentCirc, g, factory, where, attrs);
            // 6) PinLocator simple
            Map<String,Integer> nameToIdx = new LinkedHashMap<>();
            nameToIdx.put("A", 0);
            nameToIdx.put("Y", 1);

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir subcircuito: " + e.getMessage(), e);
        } catch (RuntimeException e) {
            throw new IllegalStateException("Error inesperado al añadir subcircuito: " + e.getMessage(), e);
        }
    }

    private static String safeName(String n) {
        return (n == null || n.isBlank()) ? "unnamed" : n;
    }

    private static Circuit findCircuit(LogisimFile file, String name) {
        for (Circuit c : file.getCircuits()) {
            if (c.getName().equals(name)) return c;
        }
        return null;
    }
}

