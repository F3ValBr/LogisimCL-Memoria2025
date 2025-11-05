package com.cburch.logisim.verilog.std.adapters.wordlvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.circuit.SplitterFactory;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOpParams;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.BaseComposer;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;
import com.cburch.logisim.verilog.std.macrocomponents.MacroSubcktKit;

import java.util.function.BiConsumer;

import static com.cburch.logisim.verilog.std.adapters.ComponentComposer.*;

/** Compositor de unarias ($logic_not, $reduce_*) generando submódulos compactos */
public final class UnaryOpComposer extends BaseComposer {
    private final MacroSubcktKit sub = new MacroSubcktKit();

    /* ==== Public API: devuelve la instancia del submódulo (InstanceHandle) ==== */

    /** $reduce_or/$reduce_bool(A) := NOT( A == 0 ). */
    public InstanceHandle buildReduceOrAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where, int aWidth, boolean isBool)
            throws CircuitException {
        require(ctx.fx.cmpF, "Comparator"); require(ctx.fx.notF, "NOT Gate"); require(ctx.fx.pinF, "Pin");
        final String name = MacroSubcktKit.macroName(isBool ? "reduce_bool" : "reduce_or", aWidth);

        UnaryOpParams p = new UnaryOpParams(UnaryOp.REDUCE_OR, cell.params().asMap());

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                Location cmpLoc = Location.create(200, 120);

                Component pinA = addPin(in, "A", false, aWidth, Location.create(cmpLoc.getX()-80, cmpLoc.getY()-10));
                Component pinY = addPin(in, "Y", true, 1, cmpLoc.translate(30,0));

                Component cmp = add(in, in.fx.cmpF, cmpLoc, attrsWithWidthAndLabel(in.fx.cmpF, aWidth, "A==0"));
                setComparatorSignMode(cmp.getAttributeSet(), p.aSigned());

                if (in.fx.constF != null) {
                    AttributeSet k = in.fx.constF.createAttributeSet();
                    setByNameParsed(k, "width", Integer.toString(aWidth));
                    setByNameParsed(k, "value", "0x0");
                    add(in, in.fx.constF, Location.create(cmpLoc.getX()-40, cmpLoc.getY()+10), k);
                }

                Location notLoc = cmpLoc.translate(30,0);
                Component not = add(in, in.fx.notF, notLoc, attrsWithWidthAndLabel(in.fx.notF, 1, "ne0"));

                // A -> cmp
                addWire(in, pinA.getLocation(), cmp.getLocation().translate(-40, -10));

            } catch (CircuitException e) { throw new RuntimeException(e); }
        };

        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell));
    }

    /** $reduce_and(A) := (A == 2^N - 1). */
    public InstanceHandle buildReduceAndAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where, int aWidth)
            throws CircuitException {
        require(ctx.fx.cmpF, "Comparator"); require(ctx.fx.pinF, "Pin");
        final String name = MacroSubcktKit.macroName("reduce_and", aWidth);

        UnaryOpParams p = new UnaryOpParams(UnaryOp.REDUCE_AND, cell.params().asMap());

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                Location cmpLoc = Location.create(200, 120);

                Component pinA = addPin(in, "A", false, aWidth, cmpLoc.translate(-80,-10));
                Component pinY = addPin(in, "Y", true, 1, cmpLoc);

                Component cmp = add(in, in.fx.cmpF, cmpLoc, attrsWithWidthAndLabel(in.fx.cmpF, aWidth, "A==all1"));
                setComparatorSignMode(cmp.getAttributeSet(), p.aSigned());
                if (in.fx.constF != null) {
                    AttributeSet k = in.fx.constF.createAttributeSet();
                    setByNameParsed(k, "width", Integer.toString(aWidth));
                    setByNameParsed(k, "value", hexAllOnes(aWidth));
                    add(in, in.fx.constF, Location.create(cmpLoc.getX()-40, cmpLoc.getY()+10), k);
                }

                addWire(in, pinA.getLocation(), cmp.getLocation().translate(-40, -10));

            } catch (CircuitException e) { throw new RuntimeException(e); }
        };

        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell));
    }

    /** $reduce_xor/$reduce_xnor con Parity nativa. */
    public InstanceHandle buildReduceXorAsSubckt(ComposeCtx ctx, VerilogCell cell, Location where, int aWidth, boolean odd)
            throws CircuitException {
        ComponentFactory pf = odd ? ctx.fx.oddParityF : ctx.fx.evenParityF;
        require(ctx.fx.bitExtendF, "Bit Extender"); require(ctx.fx.pinF, "Pin");
        require(pf, odd ? "Odd Parity" : "Even Parity");
        final String name = MacroSubcktKit.macroName(odd ? "reduce_xor" : "reduce_xnor", aWidth);

        BiConsumer<ComposeCtx, Circuit> populate = (in, macro) -> {
            try {
                // --- Pins A (N bits) y Y (1 bit) ---
                Component pinA = addPin(in, "A", false, aWidth, Location.create(120, 220));   // in (EAST)

                Location bitExtLoc = Location.create(pinA.getLocation().getX()+40,  pinA.getLocation().getY());
                if (in.fx.bitExtendF != null) {
                    AttributeSet beSet = in.fx.bitExtendF.createAttributeSet();
                    setByNameParsed(beSet, "in_width", Integer.toString(aWidth));
                    setByNameParsed(beSet, "out_width", "32");
                    add(in, in.fx.bitExtendF, bitExtLoc, beSet);
                }

                // --- Splitter (Wiring → Splitter) ---
                ComponentFactory splitF = SplitterFactory.instance;

                Location spLoc32to16 = bitExtLoc;
                AttributeSet sp3216Attr = splitF.createAttributeSet();
                setByNameParsed(sp3216Attr, "incoming", "32");
                setByNameParsed(sp3216Attr, "fanout", "2");
                setByNameParsed(sp3216Attr, "appear", "center");
                try { sp3216Attr.setValue(StdAttr.FACING, Direction.EAST); } catch (Exception ignore) {}
                Component splitter1 = add(in, splitF, spLoc32to16, sp3216Attr);

                Location spLoc16to0_15 = spLoc32to16.translate(20, -10);
                AttributeSet sp160_15Attr = splitF.createAttributeSet();
                setByNameParsed(sp160_15Attr, "incoming", "16");
                setByNameParsed(sp160_15Attr, "fanout", "16");
                try { sp160_15Attr.setValue(StdAttr.FACING, Direction.EAST); } catch (Exception ignore) {}
                Component splitter2 = add(in, splitF, spLoc16to0_15, sp160_15Attr);

                Location spLoc16to16_31 = spLoc32to16.translate(20, 80);
                AttributeSet sp1616_31Attr = splitF.createAttributeSet();
                setByNameParsed(sp1616_31Attr, "incoming", "16");
                setByNameParsed(sp1616_31Attr, "fanout", "16");
                setByNameParsed(sp1616_31Attr, "appear", "center");
                try { sp1616_31Attr.setValue(StdAttr.FACING, Direction.EAST); } catch (Exception ignore) {}
                Component splitter3 = add(in, splitF, spLoc16to16_31, sp1616_31Attr);

                // Wire Splitter1 → Splitter3
                addWire(in, splitter1.getLocation().translate(20, 0), splitter3.getLocation());

                Location parLoc = Location.create(splitter1.getLocation().getX()+70,  splitter1.getLocation().getY()-10);

                AttributeSet parAttr = pf.createAttributeSet();
                setByNameParsed(parAttr, "width", "1");
                setByNameParsed(parAttr, "inputs", "32");
                setByNameParsed(parAttr, "label", odd ? "odd" : "even");
                Component par = add(in, pf, parLoc, parAttr);

                Component pinY = addPin(in, "Y", true, 1, parLoc);  // out (WEST)

            } catch (CircuitException e) {
                throw new RuntimeException(e);
            }
        };

        return sub.ensureAndInstantiate(ctx, name, populate, where, lbl(cell));
    }
}
