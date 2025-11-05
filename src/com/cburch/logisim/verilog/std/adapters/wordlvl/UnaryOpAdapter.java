package com.cburch.logisim.verilog.std.adapters.wordlvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.CellParams;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.UnaryOpParams;
import com.cburch.logisim.verilog.std.*;
import com.cburch.logisim.verilog.std.adapters.MacroRegistry;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.Graphics;
import java.util.Map;

/**
 * Adapter para operaciones unarias word-level.
 * Crea NOT/BUF/NEG/POS nativos de Logisim con ancho de bus.
 * Para otras unarias genera una composicion de componentes
 * o delega al módulo (caja negra).
 */
public final class UnaryOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();
    private final MacroRegistry registry = MacroRegistry.bootUnaryDefaults();

    /** Pareja (Library, ComponentFactory) para poder resolver los mapas de puertos. */
    private record LibFactory(Library lib, ComponentFactory factory) { }

    @Override
    public boolean accepts(CellType t) {
        // Solo word-level & kind UNARY
        return t != null && t.isWordLevel() && t.isUnary();
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        UnaryOp op = UnaryOp.fromYosys(cell.type().typeId());
        try {
            // 1) Receta compuesta (si existe)
            InstanceHandle composed = tryComposeWithMacroOrNull(proj, circ, g, cell, where, registry);
            if (composed != null) return composed;

            // 2) Factory nativo (+ library) si hay
            LibFactory lf = pickFactory(proj, op);
            if (lf == null || lf.factory() == null) {
                return fallback.create(proj, circ, g, cell, where);
            }

            int width = guessUnaryWidth(cell.params());
            boolean signed = false;
            if (cell.params() instanceof UnaryOpParams up) {
                signed = up.aSigned();
            }

            AttributeSet attrs = lf.factory().createAttributeSet();
            setOptionByName(attrs, "signMode", signed ? "signed" : "unsigned");
            try { attrs.setValue(StdAttr.WIDTH, BitWidth.create(width)); } catch (Exception ignore) {}
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) {}

            Component comp = addComponent(proj, circ, g, lf.factory(), where, attrs);

            // Mapa nombre->índice específico de ESTA instancia (usa library + factory + instance)
            Map<String,Integer> nameToIdx =
                    BuiltinPortMaps.forFactory(lf.lib(), lf.factory(), comp);

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);

        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir " + op + ": " + e.getMessage(), e);
        }
    }

    /** Para sizing previo (no imprescindible si no lo usas en tu NodeSizer). */
    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        UnaryOp op = UnaryOp.fromYosys(cell.type().typeId());
        LibFactory lf = pickFactory(proj, op);
        return lf == null ? null : lf.factory();
    }

    /** Mapea BUF/NOT/LOGIC_NOT/NEG/POS a factories nativas y devuelve (lib,factory). */
    private static LibFactory pickFactory(Project proj, UnaryOp op) {
        switch (op.category()) {

            case BITWISE -> {
                // Gates: Buffer / NOT Gate
                Library gates = proj.getLogisimFile().getLibrary("Gates");
                if (gates == null) return null;
                String gateName = switch (op) {
                    case BUF -> "Buffer";
                    case NOT -> "NOT Gate";
                    default  -> null;
                };
                if (gateName == null) return null;
                ComponentFactory f = FactoryLookup.findFactory(gates, gateName);
                return (f == null) ? null : new LibFactory(gates, f);
            }

            case LOGIC -> {
                // Tu librería con lógicas de Yosys (Logical NOT Gate)
                Library yosysLib = proj.getLogisimFile().getLibrary("Yosys Components");
                if (yosysLib == null) return null;
                String name = (op == UnaryOp.LOGIC_NOT) ? "Logical NOT Gate" : null;
                if (name == null) return null;
                ComponentFactory f = FactoryLookup.findFactory(yosysLib, name);
                return (f == null) ? null : new LibFactory(yosysLib, f);
            }

            case ARITH -> {
                switch (op) {
                    case NEG -> {
                        Library arith = proj.getLogisimFile().getLibrary("Arithmetic");
                        if (arith == null) return null;
                        ComponentFactory f = FactoryLookup.findFactory(arith, "Negator");
                        return (f == null) ? null : new LibFactory(arith, f);
                    }
                    case POS -> {
                        Library gates = proj.getLogisimFile().getLibrary("Gates");
                        if (gates == null) return null;
                        ComponentFactory f = FactoryLookup.findFactory(gates, "Buffer");
                        return (f == null) ? null : new LibFactory(gates, f);
                    }
                    default -> { return null; }
                }
            }

            default -> { return null; }
        }
    }

    /** Heurística de ancho para unarias Yosys. */
    public static int guessUnaryWidth(CellParams params) {
        if (params instanceof UnaryOpParams up) {
            int aw = up.aWidth();
            int yw = up.yWidth();
            int w = Math.max(aw, yw);
            return Math.max(1, w);
        }
        if (params instanceof GenericCellParams g) {
            Object aw = g.asMap().get("A_WIDTH");
            Object yw = g.asMap().get("Y_WIDTH");
            int a = parseIntRelaxed(aw, 1);
            int y = parseIntRelaxed(yw, a > 0 ? a : 1);
            return Math.max(1, Math.max(a, y));
        }
        return 1;
    }
}
