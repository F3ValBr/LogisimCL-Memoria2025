package com.cburch.logisim.verilog.std.adapters.gatelvl;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.PortGeom;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.gatelvl.GateOp;
import com.cburch.logisim.verilog.std.AbstractComponentAdapter;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.MacroRegistry;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.*;
import java.util.Map;
import java.util.Objects;


public final class GateOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();
    private final MacroRegistry registry = MacroRegistry.bootGateDefaults();

    // Pareja (Library, ComponentFactory) para usar BuiltinPortMaps.forFactory(...)
    private record LibFactory(Library lib, ComponentFactory factory) { }

    @Override
    public boolean accepts(CellType t) {
        return t != null && t.isGateLevel() && (t.isSimpleGate() || t.isComplexGate() || t.isMultiplexer());
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        final GateOp op;
        try {
            op = GateOp.fromYosys(cell.type().typeId()); // $_AND_, $_MUX_, ...
        } catch (Exception e) {
            return fallback.create(proj, circ, g, cell, where);
        }

        // ¿hay receta macro? -> compón en el circuito destino (no el del canvas)
        InstanceHandle composed = tryComposeWithMacroOrNull(proj, circ, g, cell, where, registry);
        if (composed != null) return composed;

        LibFactory lf = pickFactoryOrNull(proj, op);
        if (lf == null || lf.factory == null) {
            return fallback.create(proj, circ, g, cell, where);
        }

        try {
            AttributeSet attrs = lf.factory.createAttributeSet();

            // --- 1) Width = 1 (gate-level 1-bit) + label “limpia”
            trySetWidthOne(attrs);
            trySetLabel(attrs, cleanCellName(cell.name()));

            // --- 2) Ajustes según puerta
            if (Objects.requireNonNull(op.category()) == GateOp.Category.MUX_FAMILY) {
                // Para 2:1 ⇒ 1 sel, 4:1 ⇒ 2, 8:1 ⇒ 3, 16:1 ⇒ 4
                Integer selBits = switch (op) {
                    case MUX   -> 1;
                    case MUX4  -> 2;
                    case MUX8  -> 3;
                    case MUX16 -> 4;
                    default -> null;
                };
                if (selBits != null) {
                    if (!setIntByName(attrs, "select", selBits)) {
                        setIntByName(attrs, "Select", selBits);
                    }
                } // éxito
            }

            if (op == GateOp.ANDNOT || op == GateOp.ORNOT) {
                setBooleanByName(attrs, "negate1", true);
            }

            Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

            Map<String, Integer> nameToIdx = BuiltinPortMaps.forFactory(lf.lib, lf.factory, comp);
            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);

        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir " + op + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        GateOp op = GateOp.fromYosys(cell.type().typeId());
        LibFactory lf = pickFactoryOrNull(proj, op);
        return lf == null ? null : lf.factory;
    }

    /** Selección de Factory según GateOp. */
    private static LibFactory pickFactoryOrNull(Project proj, GateOp op) {
        LogisimFile lf = proj.getLogisimFile();

        switch (op.category()) {

            // === 1) Puertas simples (Gates) ===
            case SIMPLE -> {
                Library lib = lf.getLibrary("Gates");
                if (lib == null) return null;

                String name = switch (op) {
                    case AND  -> "AND Gate";
                    case OR   -> "OR Gate";
                    case XOR  -> "XOR Gate";
                    case XNOR -> "XNOR Gate";
                    case NAND -> "NAND Gate";
                    case NOR  -> "NOR Gate";
                    case NOT  -> "NOT Gate";
                    case BUF  -> "Buffer";
                    default   -> null;
                };
                if (name == null) return null;

                ComponentFactory f = FactoryLookup.findFactory(lib, name);
                return (f == null) ? null : new LibFactory(lib, f);
            }

            // === 2) Combinadas (AOI/OAI) — por ahora placeholder a AND/OR base ===
            case COMBINED -> {
                Library lib = lf.getLibrary("Gates");
                if (lib == null) return null;

                String base = switch (op) {
                    case ANDNOT -> "AND Gate";
                    case ORNOT  -> "OR Gate";
                    default -> null;
                };
                if (base == null) return null;

                ComponentFactory f = FactoryLookup.findFactory(lib, base);
                return (f == null) ? null : new LibFactory(lib, f);
            }

            // === 3) MUX family ===
            case MUX_FAMILY -> {
                Library lib = lf.getLibrary("Plexers");
                if (lib == null) return null;
                ComponentFactory f = FactoryLookup.findFactory(lib, "Multiplexer");
                return (f == null) ? null : new LibFactory(lib, f);
            }

            default -> {
                return null;
            }
        }
    }

    /* ===================== Helpers de atributos ===================== */

    private static void trySetWidthOne(AttributeSet attrs) {
        try { attrs.setValue(StdAttr.WIDTH, BitWidth.ONE); } catch (Exception ignore) { }
    }

    private static void trySetLabel(AttributeSet attrs, String label) {
        if (label == null || label.isBlank()) return;
        try { attrs.setValue(StdAttr.LABEL, label); } catch (Exception ignore) { }
    }
}
