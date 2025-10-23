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
    private record LibFactory(Library lib, ComponentFactory factory) { }

    @Override
    public boolean accepts(CellType t) {
        return t != null && t.isWordLevel() && t.isBinary();
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        final BinaryOp op;
        try {
            op = BinaryOp.fromYosys(cell.type().typeId());
        } catch (Exception e) {
            return fallback.create(proj, circ, g, cell, where);
        }

        // ¿hay receta macro? -> compón en el circuito destino (no el del canvas)
        MacroRegistry.Recipe recipe = registry.find(cell.type().typeId());
        if (recipe != null) {
            var ctx = new ComposeCtx(proj, circ, g, Factories.warmup(proj));
            try {
                return recipe.build(ctx, cell, where);
            } catch (CircuitException e) {
                throw new IllegalStateException("No se pudo componer " + op + ": " + e.getMessage(), e);
            }
        }

        // 1) Elegir factory según operación ($and/$or/$xor/$xnor → Gates; $add/$sub/$mul → Arithmetic)
        LibFactory lf = pickFactoryOrNull(proj, op);
        if (lf == null || lf.factory == null) {
            // no soportado nativamente → subcircuito (en el circuito destino)
            return fallback.create(proj, circ, g, cell, where);
        }

        // 2) Width y signo desde params tipados si existen
        int width = guessBinaryWidth(cell.params()); // fallback genérico
        boolean aSigned = false, bSigned = false;
        if (cell.params() instanceof BinaryOpParams bp) {
            aSigned = bp.aSigned();
            bSigned = bp.bSigned();
        }

        try {
            AttributeSet attrs = lf.factory.createAttributeSet();

            // Ancho de bus / Etiqueta
            try { attrs.setValue(StdAttr.WIDTH, BitWidth.create(width)); } catch (Exception ignore) { }
            try { attrs.setValue(StdAttr.LABEL, cleanCellName(cell.name())); } catch (Exception ignore) { }

            // Signo (si el componente tiene el atributo correspondiente)
            applySignedModeIfAvailable(attrs, op, aSigned, bSigned);

            if (op.category() == BinaryOp.Category.SHIFT) {
                configureShifterAttributes(attrs, op, cell);
            }

            Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

            // Mapa nombre->índice específico de ESTA instancia (usa library + factory + instance)
            Map<String,Integer> nameToIdx = switch (op.category()) {
                case COMPARE -> {
                    // Selecciona cuál salida del Comparator mapeamos como “Y”
                    BuiltinPortMaps.ComparatorOut outSel = switch (op) {
                        case EQ -> BuiltinPortMaps.ComparatorOut.EQ;
                        case LT -> BuiltinPortMaps.ComparatorOut.LT;
                        case GT -> BuiltinPortMaps.ComparatorOut.GT;
                        // LE/GE/NE los compones fuera (ya tienes macros); aquí default a EQ
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
                ComponentFactory f = null;
                Library lib;

                switch (op) {
                    case SHIFT, SHIFTX -> {
                        lib = lf.getLibrary("Yosys Components");
                        if (lib != null)
                            f = FactoryLookup.findFactory(lib, "Dynamic Shifter");
                    }
                    default -> {
                        lib = lf.getLibrary("Arithmetic");
                        if (lib != null)
                            f = FactoryLookup.findFactory(lib, "Shifter");
                    }
                }

                if (lib == null || f == null) return null;
                return new LibFactory(lib, f);
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
            int w = Math.max(a, b);
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

    /** Configura el shifter (Dynamic o clásico) según el op y los params del cell. */
    private void configureShifterAttributes(AttributeSet attrs, BinaryOp op, VerilogCell cell) {
        // === 1) Leer params de Yosys (A/B/Y width, signed) ===
        int aW = 8, bW = 3, yW = 8;
        boolean aSigned = false, bSigned = false;

        CellParams p = cell.params();
        if (p instanceof BinaryOpParams bp) {
            aW = Math.max(1, bp.aWidth());
            bW = Math.max(1, bp.bWidth());
            yW = Math.max(1, bp.yWidth());
            aSigned = bp.aSigned();
            bSigned = bp.bSigned();
        } else if (p instanceof GenericCellParams g) {
            aW = Math.max(1, parseIntRelaxed(g.asMap().get("A_WIDTH"), 8));
            bW = Math.max(1, parseIntRelaxed(g.asMap().get("B_WIDTH"), 3));
            yW = Math.max(1, parseIntRelaxed(g.asMap().get("Y_WIDTH"), aW));
            aSigned = parseBoolRelaxed(g.asMap().get("A_SIGNED"), false);
            bSigned = parseBoolRelaxed(g.asMap().get("B_SIGNED"), false);
        }

        // === 2) Intentar configurar como Dynamic Shifter (si los atributos existen) ===
        boolean isDynamic =
                setOptionByName(attrs, "mode", (op == BinaryOp.SHIFTX) ? "shiftx" : "shift")
                        |  setBitWidthByName(attrs, "aWidth", aW)
                        |  setBitWidthByName(attrs, "bWidth", bW)
                        |  setBitWidthByName(attrs, "yWidth", yW)
                        |  setBooleanByName(attrs, "aSigned", aSigned)
                        |  setBooleanByName(attrs, "bSigned", bSigned);

        if (isDynamic) {
            // Ya quedó configurado en modo Dynamic Shifter. Listo.
            return;
        }

        // === 3) Determinar el modo preferido para el shift clasico ===
        String shiftId = switch (op) {
            case SHL, SSHL -> "ll"; // logical left
            case SHR       -> "lr"; // logical right
            case SSHR      -> "ar"; // arithmetic right
            default        -> "lr";
        };
        setOptionByName(attrs, "shift", shiftId);
    }
}
