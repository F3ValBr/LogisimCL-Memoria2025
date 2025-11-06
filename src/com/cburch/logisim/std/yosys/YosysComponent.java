package com.cburch.logisim.std.yosys;

import com.cburch.logisim.data.Bounds;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.tools.FactoryDescription;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.tools.Tool;
import com.cburch.logisim.util.GraphicsUtil;

import java.awt.*;
import java.util.List;

public class YosysComponent extends Library {
    private static final FactoryDescription[] DESCRIPTIONS ={
            new FactoryDescription("Logical NOT Gate", Strings.getter("logicNotGateComponent"),
                    "logicnot.gif", "LogicalNotGate"),
            new FactoryDescription("Logical AND Gate", Strings.getter("logicAndGateComponent"),
                    "logicand.gif", "LogicalAndGate"),
            new FactoryDescription("Logical OR Gate", Strings.getter("logicOrGateComponent"),
                    "logicor.gif", "LogicalOrGate"),
            new FactoryDescription("Exponent", Strings.getter("exponentComponent"),
                    "exponent.gif", "Exponent"),
            new FactoryDescription("Dynamic Shifter", Strings.getter("dynamicShifterComponent"),
                    "dynamicShifter.gif", "DynamicShifter"),
            new FactoryDescription("Bitwise Multiplexer", Strings.getter("bwmuxComponent"),
                    "bwmultiplexer.gif", "BitwiseMultiplexer"),
            new FactoryDescription("Priority Multiplexer", Strings.getter("pmuxComponent"),
                    "pmultiplexer.gif", "PriorityMultiplexer"),
            new FactoryDescription("Binary Multiplexer", Strings.getter("bmuxComponent"),
                    "bmultiplexer.gif", "BinaryMultiplexer"),
    };

    private List<Tool> tools = null;

    public YosysComponent(){ }

    @Override
    public String getName() { return "Yosys Components"; }

    @Override
    public String getDisplayName() { return Strings.get("yosysLibrary"); }

    @Override
    public List<Tool> getTools() {
        if (tools == null) {
            tools = FactoryDescription.getTools(YosysComponent.class, DESCRIPTIONS);
        }
        return tools;
    }

    static void drawTrapezoid(Graphics g, Bounds bds, Direction facing,
                              int facingLean) {
        int wid = bds.getWidth();
        int ht = bds.getHeight();
        int x0 = bds.getX(); int x1 = x0 + wid;
        int y0 = bds.getY(); int y1 = y0 + ht;
        int[] xp = { x0, x1, x1, x0 };
        int[] yp = { y0, y0, y1, y1 };
        if (facing == Direction.WEST) {
            yp[0] += facingLean; yp[3] -= facingLean;
        } else if (facing == Direction.NORTH) {
            xp[0] += facingLean; xp[1] -= facingLean;
        } else if (facing == Direction.SOUTH) {
            xp[2] -= facingLean; xp[3] += facingLean;
        } else {
            yp[1] += facingLean; yp[2] -= facingLean;
        }
        GraphicsUtil.switchToWidth(g, 2);
        g.drawPolygon(xp, yp, 4);
    }

    static boolean contains(Location loc, Bounds bds, Direction facing) {
        if (bds.contains(loc, 1)) {
            int x = loc.getX();
            int y = loc.getY();
            int x0 = bds.getX();
            int x1 = x0 + bds.getWidth();
            int y0 = bds.getY();
            int y1 = y0 + bds.getHeight();
            if (facing == Direction.NORTH || facing == Direction.SOUTH) {
                if (x < x0 + 5 || x > x1 - 5) {
                    if (facing == Direction.SOUTH) {
                        return y < y0 + 5;
                    } else {
                        return y > y1 - 5;
                    }
                } else {
                    return true;
                }
            } else {
                if (y < y0 + 5 || y > y1 - 5) {
                    if (facing == Direction.EAST) {
                        return x < x0 + 5;
                    } else {
                        return x > x1 - 5;
                    }
                } else {
                    return true;
                }
            }
        } else {
            return false;
        }
    }
}
