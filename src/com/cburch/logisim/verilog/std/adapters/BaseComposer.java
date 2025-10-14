package com.cburch.logisim.verilog.std.adapters;

import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.std.wiring.Pin;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.std.Strings;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;

import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.cleanCellName;
import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.setParsedIfPresent;
import static com.cburch.logisim.verilog.std.adapters.ComponentComposer.setByNameParsed;

/** Base con helpers compartidos para todos los compositores. */
public abstract class BaseComposer {
    protected Component add(ComposeCtx ctx,
                            ComponentFactory f,
                            Location loc,
                            AttributeSet attrs)
            throws CircuitException {
        Component comp = f.createComponent(loc, attrs);

        if (ctx.circ.hasConflict(comp)) {
            throw new CircuitException(Strings.get("exclusiveError"));
        }

        Bounds b = comp.getBounds(ctx.g);
        if (b.getX() < 0 || b.getY() < 0) {
            throw new CircuitException(Strings.get("negativeCoordError"));
        }

        CircuitMutation m = new CircuitMutation(ctx.circ);
        m.add(comp);
        ctx.proj.doAction(m.toAction(Strings.getter("addComponentAction", f.getDisplayGetter())));
        return comp;
    }

    protected void addWire(ComposeCtx ctx, Location p1, Location p2) throws CircuitException {
        Wire w = Wire.create(p1, p2);

        CircuitMutation m = new CircuitMutation(ctx.circ);
        m.add(w);
        ctx.proj.doAction(m.toAction(Strings.getter("addWireAction")));
    }

    protected Component addPin(ComposeCtx ctx, String name, boolean isOutput, int width, Location where)
            throws CircuitException {
        if (ctx.fx.pinF == null) throw new CircuitException(Strings.get("noPinFactoryError"));
        AttributeSet a = ctx.fx.pinF.createAttributeSet();
        try { a.setValue(StdAttr.LABEL, name); } catch (Exception ignore) {}
        try { a.setValue(StdAttr.WIDTH, BitWidth.create(Math.max(1, width))); } catch (Exception ignore) {}
        try { a.setValue(StdAttr.FACING, isOutput ? Direction.WEST : Direction.EAST); } catch (Exception ignore) {}
        try { a.setValue(Pin.ATTR_TRISTATE, false); } catch (Exception ignore) {}
        setByNameParsed(a, "output", Boolean.toString(isOutput));
        return add(ctx, ctx.fx.pinF, where, a);
    }

    protected String lbl(VerilogCell cell){ return cleanCellName(cell.name()); }

    protected static void require(ComponentFactory f, String name) throws CircuitException {
        if (f == null) {
            String tmpl = Strings.get("missingFactoryError");
            String msg  = tmpl.contains("%s") ? String.format(tmpl, name) : (tmpl + " " + name);
            throw new CircuitException(msg);
        }
    }

    /** Intenta setear el modo de signo en un comparador (SIGNED / UNSIGNED). */
    protected static void setComparatorSignMode(AttributeSet attrs, boolean signed) {
        // Soporta ambos tipos de atributos: "mode" o "signMode"
        if (!setParsedIfPresent(attrs, "signMode", signed ? "signed" : "unsigned")) {
            setParsedIfPresent(attrs, "mode", signed ? "twosComplement" : "unsigned");
        }
    }
}
