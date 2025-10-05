package com.cburch.logisim.verilog.std.adapters.wordlvl;

// BinaryOpAdapter.java

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.*;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.CellParams;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.BinaryOpParams;
import com.cburch.logisim.verilog.std.*;
import com.cburch.logisim.verilog.std.adapters.MacroRegistry;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;
import com.cburch.logisim.verilog.std.macrocomponents.Factories;

import java.awt.Graphics;
import java.util.Map;

public final class BinaryOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();
    private final MacroRegistry registry = MacroRegistry.bootBinaryDefaults();

    // Pareja (Library, ComponentFactory) para poder resolver port maps por librería
    private static final class LibFactory {
        final Library lib;
        final ComponentFactory factory;
        LibFactory(Library lib, ComponentFactory factory) { this.lib = lib; this.factory = factory; }
    }

    @Override
    public boolean accepts(CellType t) {
        return t != null && t.isWordLevel() && t.isBinary();
    }

    @Override
    public InstanceHandle create(Canvas canvas, Graphics g, VerilogCell cell, Location where) {
        final BinaryOp op;
        try {
            op = BinaryOp.fromYosys(cell.type().typeId());
        } catch (Exception e) {
            return fallback.create(canvas, g, cell, where);
        }

        MacroRegistry.Recipe recipe = registry.find(cell.type().typeId());
        if (recipe != null) {
            var ctx = new ComposeCtx(canvas.getProject(), canvas.getCircuit(), g, Factories.warmup(canvas.getProject()));
            try {
                return recipe.build(ctx, cell, where);
            } catch (CircuitException e) {
                throw new IllegalStateException("No se pudo componer " + op + ": " + e.getMessage(), e);
            }
        }

        // 1) Elegir factory según operación ($and/$or/$xor/$xnor → Gates; $add/$sub/$mul → Arithmetic)
        LibFactory lf = pickFactoryOrNull(canvas.getProject(), op);
        if (lf == null || lf.factory == null) {
            // no soportado nativamente → subcircuito
            return fallback.create(canvas, g, cell, where);
        }

        // 2) Width y signo desde params tipados si existen
        int width = guessBinaryWidth(cell.params()); // fallback genérico

        boolean aSigned = false, bSigned = false;
        if (cell.params() instanceof BinaryOpParams bp) {
            // width preferente (Y_WIDTH)
            width   = Math.max(1, bp.yWidth() > 0 ? bp.yWidth()
                    : Math.max(bp.aWidth(), Math.max(bp.bWidth(), width)));
            aSigned = bp.aSigned();
            bSigned = bp.bSigned();
        }

        try {
            Project proj = canvas.getProject();
            Circuit circ = canvas.getCircuit();

            AttributeSet attrs = lf.factory.createAttributeSet();

            // Ancho de bus
            try { attrs.setValue(StdAttr.WIDTH, BitWidth.create(width)); } catch (Exception ignore) { }
            // Etiqueta
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) { }

            // Signo
            applySignedModeIfAvailable(attrs, op, aSigned, bSigned);

            // Nota: Para $add/$sub Logisim “Adder/Subtractor” tienen pines Cin/Cout.
            // Aquí solo creamos el componente; el cableado (p. ej. Cin=0) lo resolverás en tu fase de wiring/túneles.

            Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

            // Mapa nombre->índice específico de ESTA instancia (usa library + factory + instance)
            Map<String,Integer> nameToIdx = switch (op.category()) {
                case COMPARE -> {
                    // Selecciona qué pin del Comparator debe ser “Y”
                    BuiltinPortMaps.ComparatorOut outSel = switch (op) {
                        case EQ -> BuiltinPortMaps.ComparatorOut.EQ;
                        case LT -> BuiltinPortMaps.ComparatorOut.LT;
                        case GT -> BuiltinPortMaps.ComparatorOut.GT;
                        // Si quieres cubrir LE/GE/NE más adelante, compón con puertas extra o
                        // crea otra ruta. Por ahora, default a EQ.
                        default -> BuiltinPortMaps.ComparatorOut.EQ;
                    };
                    yield BuiltinPortMaps.forComparator(lf.lib, lf.factory, comp, outSel);
                }
                default -> BuiltinPortMaps.forFactory(lf.lib, lf.factory, comp);
            };

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir " + op + ": " + e.getMessage(), e);
        }
    }

    /** Aplica el “modo de signo” si el factory lo expone.
     *  - Comparadores:   mode = twosComplement|unsigned
     *  - Aritmética:     signMode = signed|unsigned|pin|auto  (usamos signed/unsigned)
     *  - Fallback:       booleano "signed" si existe
     */
    private void applySignedModeIfAvailable(AttributeSet attrs, BinaryOp op,
                                            boolean aSigned, boolean bSigned) {
        boolean signed = aSigned || bSigned;

        // 1) Intento “signMode” (unsigned/signed/pin/auto). Preferimos signed/unsigned fijos.
        boolean ok = false;
        ok |= setOptionByName(attrs, "signMode", signed ? "signed" : "unsigned");

        // 2) Comparadores: “mode” = twosComplement|unsigned
        if (!ok && op.category() == BinaryOp.Category.COMPARE) {
            ok |= setOptionByName(attrs, "mode", signed ? "twosComplement" : "unsigned");
        }

        // 3) Booleano "signed"
        if (!ok) ok |= setBooleanByName(attrs, "signed", signed);

        // 4) Fallbacks suaves (por si tu factory usa otros nombres)
        if (!ok) {
            ok |= setOptionByName(attrs, "arithMode", signed ? "signed" : "unsigned");
            ok |= setOptionByName(attrs, "comparisonMode", signed ? "twosComplement" : "unsigned");
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        BinaryOp op = BinaryOp.fromYosys(cell.type().typeId());
        LibFactory lf = pickFactoryOrNull(proj, op);
        return lf == null ? null : lf.factory;
    }

    /** Selecciona el ComponentFactory nativo de Logisim según la operación. */
    private static LibFactory pickFactoryOrNull(Project proj, BinaryOp op) {
        LogisimFile lf = proj.getLogisimFile();

        // Gates clásicos
        switch (op.category()) {
            case BITWISE -> {
                Library gates = lf.getLibrary("Gates");
                if (gates == null) return null;
                String gateName = switch (op) {
                    case AND  -> "AND Gate";
                    case OR   -> "OR Gate";
                    case XOR  -> "XOR Gate";
                    case XNOR -> "XNOR Gate";
                    default   -> null;
                };
                ComponentFactory f = FactoryLookup.findFactory(gates, gateName);
                return (f == null) ? null : new LibFactory(gates, f);
            }
            case LOGIC -> {
                // Tus “Logical AND/OR” viven en tu librería Yosys Components
                Library yosys = lf.getLibrary("Yosys Components");
                if (yosys == null) return null;
                String gateName = switch (op) {
                    case LOGIC_AND -> "Logical AND Gate";
                    case LOGIC_OR  -> "Logical OR Gate";
                    default        -> null;
                };
                ComponentFactory f = FactoryLookup.findFactory(yosys, gateName);
                return (f == null) ? null : new LibFactory(yosys, f);
            }
            case ARITH -> {
                if (op == BinaryOp.POW) {
                    Library yosys = lf.getLibrary("Yosys Components");
                    if (yosys == null) return null;
                    ComponentFactory f = FactoryLookup.findFactory(yosys, "Exponent");
                    return (f == null) ? null : new LibFactory(yosys, f);
                }
                // Aritméticos (suma/resta/mult/div/mod/…)
                Library arith = lf.getLibrary("Arithmetic");
                if (arith == null) return null;
                String name = switch (op) {
                    case ADD -> "Adder";
                    case SUB -> "Subtractor";
                    case MUL -> "Multiplier";
                    case DIV, MOD, DIVFLOOR, MODFLOOR -> "Divider";
                    default -> null;
                };
                ComponentFactory f = FactoryLookup.findFactory(arith, name);
                return (f == null) ? null : new LibFactory(arith, f);
            }
            case COMPARE -> {
                // Comparadores → usar Comparator (con pines eq/lt/gt)
                Library arith = lf.getLibrary("Arithmetic");
                if (arith == null) return null;
                ComponentFactory f = FactoryLookup.findFactory(arith, "Comparator");
                return (f == null) ? null : new LibFactory(arith, f);
            }
            case SHIFT -> {
                // Shifts → usar Shifter (con pin out)
                Library arith = lf.getLibrary("Arithmetic");
                if (arith == null) return null;
                ComponentFactory f = FactoryLookup.findFactory(arith, "Shifter");
                return (f == null) ? null : new LibFactory(arith, f);
            }
            default -> {
                // Otros binarios (mashups raros) → no mapeados aquí
                return null;
            }
        }
    }

    /** Heurística para WIDTH en binarios Yosys. */
    private static int guessBinaryWidth(CellParams params) {
        if (params instanceof BinaryOpParams bp) {
            int y = bp.yWidth();
            int a = bp.aWidth();
            int b = bp.bWidth();
            int w = (y > 0) ? y : Math.max(a, b);
            return Math.max(1, w);
        }
        if (params instanceof GenericCellParams g) {
            Object aw = g.asMap().get("A_WIDTH");
            Object bw = g.asMap().get("B_WIDTH");
            Object yw = g.asMap().get("Y_WIDTH");
            int a = parseIntRelaxed(aw, 1);
            int b = parseIntRelaxed(bw, a);
            int y = parseIntRelaxed(yw, Math.max(a, b));
            return Math.max(1, Math.max(Math.max(a, b), y));
        }
        return 1;
    }
}
