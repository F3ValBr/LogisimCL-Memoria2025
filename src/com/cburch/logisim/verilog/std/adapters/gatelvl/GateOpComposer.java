package com.cburch.logisim.verilog.std.adapters.gatelvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.BaseComposer;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;
import com.cburch.logisim.verilog.std.macrocomponents.MacroSubcktKit;

import java.util.List;
import java.util.function.BiConsumer;

import static com.cburch.logisim.verilog.std.adapters.ComponentComposer.attrsWithWidthAndLabel;


public class GateOpComposer extends BaseComposer {
    private final MacroSubcktKit sub = new MacroSubcktKit();

    public InstanceHandle buildAOI3AsSubckt(ComposeCtx ctx, VerilogCell cell, Location where) {
        require(ctx.fx.andF, "AND Gate"); require(ctx.fx.orF, "OR Gate"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("aoi3");

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            Location andLoc = Location.create(200,120);

            Component pinA = addPin(in, "A", false, 1, andLoc.translate(-40, -10));
            Component pinB = addPin(in, "B", false, 1, andLoc.translate(-40, 10));
            Component pinC = addPin(in, "C", false, 1, andLoc.translate(-40, 30));
            Component pinY = addPin(in, "Y", true, 1, andLoc.translate(90, 10));

            Component and = add(in, ctx.fx.andF, andLoc,
                    attrsWithWidthAndLabel(ctx.fx.andF, 1, "AND"));

            addWire(in, pinA.getLocation(), and.getLocation().translate(-30, -10));
            addWire(in, pinB.getLocation(), and.getLocation().translate(-30, 10));

            Location orLoc = Location.create(and.getLocation().getX() + 40, and.getLocation().getY() + 10);

            Component or = add(in, ctx.fx.orF, orLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "OR"));

            addWire(in, and.getLocation(), and.getLocation().translate(10, 0));
            addWire(in, pinC.getLocation(), pinC.getLocation().translate(40, 0));
            addWire(in, pinC.getLocation().translate(40, 0), or.getLocation().translate(-40, 10));
            addWire(in, or.getLocation().translate(-40, 10), or.getLocation().translate(-30, 10));

            Location notLoc = Location.create(or.getLocation().getX() + 40, or.getLocation().getY());

            Component not = add(in, ctx.fx.notF, notLoc,
                    attrsWithWidthAndLabel(ctx.fx.notF, 1, "NOT"));

            addWire(in, or.getLocation(), not.getLocation().translate(-30, 0));
            addWire(in, not.getLocation(), pinY.getLocation());
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "C", "Y"));
    }

    public InstanceHandle buildAOI4AsSubckt(ComposeCtx ctx, VerilogCell cell, Location where) {
        require(ctx.fx.andF, "AND Gate"); require(ctx.fx.orF, "OR Gate"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("aoi4");

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            Location andLoc = Location.create(200,120);

            Component pinA = addPin(in, "A", false, 1, andLoc.translate(-40, -10));
            Component pinB = addPin(in, "B", false, 1, andLoc.translate(-40, 10));
            Component pinC = addPin(in, "C", false, 1, andLoc.translate(-40, 30));
            Component pinD = addPin(in, "D", false, 1, andLoc.translate(-40, 50));
            Component pinY = addPin(in, "Y", true, 1, andLoc.translate(100, 10));

            Component andAB = add(in, ctx.fx.andF, andLoc,
                    attrsWithWidthAndLabel(ctx.fx.andF, 1, "AND"));

            addWire(in, pinA.getLocation(), andAB.getLocation().translate(-30, -10));
            addWire(in, pinB.getLocation(), andAB.getLocation().translate(-30, 10));

            Location andCDLoc = Location.create(andLoc.getX(), andLoc.getY() + 40);

            Component andCD = add(in, ctx.fx.andF, andCDLoc,
                    attrsWithWidthAndLabel(ctx.fx.andF, 1, "AND"));

            addWire(in, pinC.getLocation(), pinC.getLocation().translate(10, 0));
            addWire(in, pinD.getLocation(), pinD.getLocation().translate(10, 0));

            Location orLoc = Location.create(andAB.getLocation().getX() + 50, andAB.getLocation().getY() + 10);

            Component or = add(in, ctx.fx.orF, orLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "OR"));

            addWire(in, andAB.getLocation(), andAB.getLocation().translate(20, 0));
            addWire(in, andCD.getLocation(), andCD.getLocation().translate(10, 0));
            addWire(in, andCD.getLocation().translate(10,0), or.getLocation().translate(-40, 10));
            addWire(in, or.getLocation().translate(-40, 10), or.getLocation().translate(-30, 10));

            Location notLoc = Location.create(or.getLocation().getX() + 40, or.getLocation().getY());

            Component not = add(in, ctx.fx.notF, notLoc,
                    attrsWithWidthAndLabel(ctx.fx.notF, 1, "NOT"));

            addWire(in, or.getLocation(), not.getLocation().translate(-30, 0));
            addWire(in, not.getLocation(), pinY.getLocation());
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "C", "D", "Y"));
    }

    public InstanceHandle buildOAI3AsSubckt(ComposeCtx ctx, VerilogCell cell, Location where) {
        require(ctx.fx.andF, "AND Gate"); require(ctx.fx.orF, "OR Gate"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("oai3");

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            Location orLoc = Location.create(200,120);

            Component pinA = addPin(in, "A", false, 1, orLoc.translate(-40, -10));
            Component pinB = addPin(in, "B", false, 1, orLoc.translate(-40, 10));
            Component pinC = addPin(in, "C", false, 1, orLoc.translate(-40, 30));
            Component pinY = addPin(in, "Y", true, 1, orLoc.translate(90, 10));

            Component or = add(in, ctx.fx.orF, orLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "OR"));

            addWire(in, pinA.getLocation(), or.getLocation().translate(-30, -10));
            addWire(in, pinB.getLocation(), or.getLocation().translate(-30, 10));

            Location andLoc = Location.create(or.getLocation().getX() + 40, or.getLocation().getY() + 10);

            Component and = add(in, ctx.fx.andF, andLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "AND"));

            addWire(in, or.getLocation(), or.getLocation().translate(10, 0));
            addWire(in, pinC.getLocation(), pinC.getLocation().translate(40, 0));
            addWire(in, pinC.getLocation().translate(40, 0), and.getLocation().translate(-40, 10));
            addWire(in, and.getLocation().translate(-40, 10), and.getLocation().translate(-30, 10));

            Location notLoc = Location.create(and.getLocation().getX() + 40, and.getLocation().getY());

            Component not = add(in, ctx.fx.notF, notLoc,
                    attrsWithWidthAndLabel(ctx.fx.notF, 1, "NOT"));

            addWire(in, and.getLocation(), not.getLocation().translate(-30, 0));
            addWire(in, not.getLocation(), pinY.getLocation());
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "C", "Y"));
    }

    public InstanceHandle buildOAI4AsSubckt(ComposeCtx ctx, VerilogCell cell, Location where) {
        require(ctx.fx.andF, "AND Gate"); require(ctx.fx.orF, "OR Gate"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("oai4");

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            Location orLoc = Location.create(200,120);

            Component pinA = addPin(in, "A", false, 1, orLoc.translate(-40, -10));
            Component pinB = addPin(in, "B", false, 1, orLoc.translate(-40, 10));
            Component pinC = addPin(in, "C", false, 1, orLoc.translate(-40, 30));
            Component pinD = addPin(in, "D", false, 1, orLoc.translate(-40, 50));
            Component pinY = addPin(in, "Y", true, 1, orLoc.translate(100, 10));

            Component orAB = add(in, ctx.fx.orF, orLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "OR"));

            addWire(in, pinA.getLocation(), orAB.getLocation().translate(-30, -10));
            addWire(in, pinB.getLocation(), orAB.getLocation().translate(-30, 10));

            Location orCDLoc = Location.create(orLoc.getX(), orLoc.getY() + 40);

            Component orCD = add(in, ctx.fx.orF, orCDLoc,
                    attrsWithWidthAndLabel(ctx.fx.orF, 1, "OR"));

            addWire(in, pinC.getLocation(), pinC.getLocation().translate(10, 0));
            addWire(in, pinD.getLocation(), pinD.getLocation().translate(10, 0));

            Location andLoc = Location.create(orAB.getLocation().getX() + 50, orAB.getLocation().getY() + 10);

            Component and = add(in, ctx.fx.andF, andLoc,
                    attrsWithWidthAndLabel(ctx.fx.andF, 1, "AND"));

            addWire(in, orAB.getLocation(), orAB.getLocation().translate(20, 0));
            addWire(in, orCD.getLocation(), orCD.getLocation().translate(10, 0));
            addWire(in, orCD.getLocation().translate(10,0), and.getLocation().translate(-40, 10));
            addWire(in, and.getLocation().translate(-40, 10), and.getLocation().translate(-30, 10));

            Location notLoc = Location.create(and.getLocation().getX() + 40, and.getLocation().getY());

            Component not = add(in, ctx.fx.notF, notLoc,
                    attrsWithWidthAndLabel(ctx.fx.notF, 1, "NOT"));

            addWire(in, and.getLocation(), not.getLocation().translate(-30, 0));
            addWire(in, not.getLocation(), pinY.getLocation());
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "C", "D", "Y"));
    }

    public InstanceHandle buildNMuxAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where) {
        require(ctx.fx.andF, "Multiplexer"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("nmux");

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            Location muxLoc = Location.create(200,120);
            Component pinA = addPin(in, "A", false, 1, muxLoc.translate(-50, -10));
            Component pinB = addPin(in, "B", false, 1, muxLoc.translate(-50, 10));
            Component pinS = addPin(in, "S", false, 1, muxLoc.translate(-50, 30));
            Component pinY = addPin(in, "Y", true, 1, muxLoc.translate(50, 0));

            Component mux = add(in, ctx.fx.muxF, muxLoc,
                    attrsWithWidthAndLabel(ctx.fx.muxF, 1, "NMUX"));

            addWire(in, pinA.getLocation(), pinA.getLocation().translate(20,0));
            addWire(in, pinB.getLocation(), pinB.getLocation().translate(20,0));
            addWire(in, pinS.getLocation(), pinS.getLocation().translate(30,0));
            addWire(in, pinS.getLocation().translate(30,0), pinS.getLocation().translate(30,-10));

            addWire(in, muxLoc, muxLoc.translate(10, 0));

            Location notLoc = Location.create(muxLoc.getX() + 40, muxLoc.getY());
            Component not = add(in, ctx.fx.notF, notLoc,
                    attrsWithWidthAndLabel(ctx.fx.notF, 1, "NOT"));

            addWire(in, not.getLocation(), not.getLocation().translate(10, 0));
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "S", "Y"));
    }
}
