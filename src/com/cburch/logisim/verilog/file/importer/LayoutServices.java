package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.layout.builder.LayoutBuilder;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.awt.Graphics;
import java.util.*;

final class LayoutServices {
    record PortAnchor(Location loc, Direction facing) { }

    private final int minX, minY, grid, padX, separationInputCells;

    LayoutServices(int minX, int minY, int grid, int padX, int sep) {
        this.minX = minX; this.minY = minY; this.grid = grid; this.padX = padX; this.separationInputCells = sep;
    }
    int minX(){ return minX; }
    int minY(){ return minY; }
    int separationInputCells(){ return separationInputCells; }

    void addModulePins(Project proj,
                       Circuit circuit,
                       VerilogModuleImpl mod,
                       LayoutBuilder.Result elk,
                       Graphics g,
                       Map<ModulePort, PortAnchor> topAnchors) {

        Bounds bb = ImporterUtils.Geom.cellsBounds(elk);
        int left = bb.getX(), right = bb.getX() + bb.getWidth();
        int top = bb.getY(), bottom = bb.getY() + bb.getHeight();

        int xInputs  = ImporterUtils.Geom.snap(left  - padX);
        int xOutputs = ImporterUtils.Geom.snap(right + padX);

        List<ModulePort> inputs  = mod.ports().stream().filter(p -> p.direction() == PortDirection.INPUT).toList();
        List<ModulePort> outputs = mod.ports().stream().filter(p -> p.direction() == PortDirection.OUTPUT).toList();

        int spanY = Math.max(1, bottom - top);

        int inStep = Math.max(grid, spanY / Math.max(1, inputs.size() + 1));
        int curInY = top + inStep;

        for (ModulePort p : inputs) {
            AttributeSet a = Pin.FACTORY.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, p.width())));
            a.setValue(Pin.ATTR_TYPE, false);
            a.setValue(Pin.ATTR_TRISTATE, false);
            a.setValue(StdAttr.FACING, Direction.EAST);
            a.setValue(StdAttr.LABEL, p.name());

            Location loc = Location.create(ImporterUtils.Geom.snap(xInputs), ImporterUtils.Geom.snap(curInY));
            curInY += inStep;

            Component c = ImporterUtils.Components.addComponentSafe(proj, circuit, g, Pin.FACTORY, loc, a);
            topAnchors.put(p, new PortAnchor(c.getLocation(), Direction.EAST));
        }

        int outStep = Math.max(grid, spanY / Math.max(1, outputs.size() + 1));
        int curOutY = top + outStep;

        for (ModulePort p : outputs) {
            AttributeSet a = Pin.FACTORY.createAttributeSet();
            a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, p.width())));
            a.setValue(Pin.ATTR_TYPE, true);
            a.setValue(Pin.ATTR_TRISTATE, false);
            a.setValue(StdAttr.FACING, Direction.WEST);
            a.setValue(StdAttr.LABEL, p.name());
            a.setValue(Pin.ATTR_LABEL_LOC, Direction.EAST);

            Location loc = Location.create(ImporterUtils.Geom.snap(xOutputs), ImporterUtils.Geom.snap(curOutY));
            curOutY += outStep;

            Component c = ImporterUtils.Components.addComponentSafe(proj, circuit, g, Pin.FACTORY, loc, a);
            topAnchors.put(p, new PortAnchor(c.getLocation(), Direction.WEST));
        }
    }

    static Direction facingByNearestBorder(Bounds cb, Location pinLoc) {
        int left   = cb.getX();
        int right  = cb.getX() + cb.getWidth();
        int top    = cb.getY();
        int bottom = cb.getY() + cb.getHeight();

        int dxL = Math.abs(pinLoc.getX() - left);
        int dxR = Math.abs(pinLoc.getX() - right);
        int dyT = Math.abs(pinLoc.getY() - top);
        int dyB = Math.abs(pinLoc.getY() - bottom);

        Direction facing;
        int min = dxL; facing = Direction.EAST;
        if (dxR < min) { min = dxR; facing = Direction.WEST; }
        if (dyT < min) { min = dyT; facing = Direction.SOUTH; }
        if (dyB < min) { facing = Direction.NORTH; }
        return facing;
    }
}
