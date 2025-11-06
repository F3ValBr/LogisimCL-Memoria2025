package com.cburch.logisim.verilog.std.adapters.wordlvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOpParams;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.BaseComposer;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;
import com.cburch.logisim.verilog.std.macrocomponents.MacroSubcktKit;

import java.util.List;
import java.util.function.BiConsumer;

import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.setBooleanByName;
import static com.cburch.logisim.verilog.std.adapters.ComponentComposer.attrsWithWidthAndLabel;

public final class BinaryOpComposer extends BaseComposer {
    private final MacroSubcktKit sub = new MacroSubcktKit();

    /** Y = (A != B) = NOT (A == B) */
    public InstanceHandle buildNeAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where,
                                          int aWidth, int bWidth, boolean strict) throws CircuitException {
        require(ctx.fx.cmpF, "Comparator"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("ne(!=)", aWidth, bWidth);

        BinaryOpParams p = new BinaryOpParams(BinaryOp.NE, cell.params().asMap());

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                Location cLoc = Location.create(200, 120);

                Component pinA = addPin(in, "A", false, aWidth, cLoc.translate(-80, -40));
                Component pinB = addPin(in, "B", false, bWidth, cLoc.translate(-80,  40));
                Component pinY = addPin(in, "Y", true, 1, cLoc.translate(60,    0));

                // Comparator
                Component cmp = add(in, in.fx.cmpF, cLoc,
                        attrsWithWidthAndLabel(in.fx.cmpF, Math.max(aWidth, bWidth), "CMP"));

                setBooleanByName(cmp.getAttributeSet(), "strictEq", strict);

                // 👉 Setear signo (usa los flags de BinaryOpParams)
                setComparatorSignMode(cmp.getAttributeSet(), p.aSigned() || p.bSigned());

                // Wiring A->cmp.A (izquierda alta)
                addWire(in, pinA.getLocation(), pinA.getLocation().translate(20, 0));
                addWire(in, pinA.getLocation().translate(20, 0), cLoc.translate(-60, -10));
                addWire(in, cLoc.translate(-60, -10), cLoc.translate(-40, -10));

                // Wiring B->cmp.B (izquierda baja)
                addWire(in, pinB.getLocation(), pinB.getLocation().translate(20, 0));
                addWire(in, pinB.getLocation().translate(20, 0), cLoc.translate(-60,  10));
                addWire(in, cLoc.translate(-60, 10), cLoc.translate(-40, 10));

                // eq -> Y  (derecha alta → hacia Y)
                addWire(in, cLoc, cLoc.translate(10, 0));
                Component not = add(in, in.fx.notF, cLoc.translate(40, 0),
                        attrsWithWidthAndLabel(in.fx.notF, 1, "NE"));
                addWire(in, cLoc.translate(40, 0), pinY.getLocation());

            } catch (CircuitException e) { throw new RuntimeException(e); }
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "Y"));
    }

    /** Y = (A <= B) = (A < B) OR (A == B) */
    public InstanceHandle buildLeAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where,
                                          int aWidth, int bWidth) throws CircuitException {
        require(ctx.fx.cmpF, "Comparator"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("le(<=)", aWidth, bWidth);

        BinaryOpParams p = new BinaryOpParams(BinaryOp.LE, cell.params().asMap());

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                Location cLoc = Location.create(200, 120);

                Component pinA = addPin(in, "A", false, aWidth, cLoc.translate(-80, -40));
                Component pinB = addPin(in, "B", false, bWidth, cLoc.translate(-80,  40));
                Component pinY = addPin(in, "Y", true, 1, cLoc.translate(60,  -10));

                // Comparator
                Component cmp = add(in, in.fx.cmpF, cLoc,
                        attrsWithWidthAndLabel(in.fx.cmpF, Math.max(aWidth, bWidth), "CMP"));

                // 👉 Setear signo (usa los flags de BinaryOpParams)
                setComparatorSignMode(cmp.getAttributeSet(), p.aSigned() || p.bSigned());

                // Wiring A->cmp.A (izquierda alta)
                addWire(in, pinA.getLocation(), pinA.getLocation().translate(20, 0));
                addWire(in, pinA.getLocation().translate(20, 0), cLoc.translate(-60, -10));
                addWire(in, cLoc.translate(-60, -10), cLoc.translate(-40, -10));

                // Wiring B->cmp.B (izquierda baja)
                addWire(in, pinB.getLocation(), pinB.getLocation().translate(20, 0));
                addWire(in, pinB.getLocation().translate(20, 0), cLoc.translate(-60,  10));
                addWire(in, cLoc.translate(-60, 10), cLoc.translate(-40, 10));

                // eq -> Y  (derecha alta → hacia Y)
                addWire(in, cLoc.translate(0, -10), cLoc.translate(10, -10));
                Component not = add(in, in.fx.notF, cLoc.translate(40, -10),
                        attrsWithWidthAndLabel(in.fx.notF, 1, "NE"));
                addWire(in, cLoc.translate(40, -10), pinY.getLocation());

            } catch (CircuitException e) { throw new RuntimeException(e); }
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "Y"));
    }

    /** Y = (A >= B) = (A > B) OR (A == B) */
    public InstanceHandle buildGeAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where,
                                          int aWidth, int bWidth) throws CircuitException {
        require(ctx.fx.cmpF, "Comparator"); require(ctx.fx.notF, "NOT Gate");
        final String name = MacroSubcktKit.macroName("ge(>=)", aWidth, bWidth);

        BinaryOpParams p = new BinaryOpParams(BinaryOp.GE, cell.params().asMap());

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                Location cLoc = Location.create(200, 120);

                Component pinA = addPin(in, "A", false, aWidth, cLoc.translate(-80, -40));
                Component pinB = addPin(in, "B", false, bWidth, cLoc.translate(-80,  40));
                Component pinY = addPin(in, "Y", true, 1, cLoc.translate(60,  10));

                // Comparator
                Component cmp = add(in, in.fx.cmpF, cLoc,
                        attrsWithWidthAndLabel(in.fx.cmpF, Math.max(aWidth, bWidth), "CMP"));

                // 👉 Setear signo (usa los flags de BinaryOpParams)
                setComparatorSignMode(cmp.getAttributeSet(), p.aSigned() || p.bSigned());

                // Wiring A->cmp.A (izquierda alta)
                addWire(in, pinA.getLocation(), pinA.getLocation().translate(20, 0));
                addWire(in, pinA.getLocation().translate(20, 0), cLoc.translate(-60, -10));
                addWire(in, cLoc.translate(-60, -10), cLoc.translate(-40, -10));

                // Wiring B->cmp.B (izquierda baja)
                addWire(in, pinB.getLocation(), pinB.getLocation().translate(20, 0));
                addWire(in, pinB.getLocation().translate(20, 0), cLoc.translate(-60,  10));
                addWire(in, cLoc.translate(-60, 10), cLoc.translate(-40, 10));

                // eq -> Y  (derecha alta → hacia Y)
                addWire(in, cLoc.translate(0, 10), cLoc.translate(10, 10));
                Component not = add(in, in.fx.notF, cLoc.translate(40, 10),
                        attrsWithWidthAndLabel(in.fx.notF, 1, "NE"));
                addWire(in, cLoc.translate(40, 10), pinY.getLocation());
            } catch (CircuitException e) { throw new RuntimeException(e); }
        };
        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell),
                List.of("A", "B", "Y"));
    }
}
