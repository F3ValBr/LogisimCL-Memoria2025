package com.cburch.logisim.verilog.std.adapters;

import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.CellParams;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.wordlvl.BinaryOpComposer;
import com.cburch.logisim.verilog.std.adapters.wordlvl.UnaryOpComposer;
import com.cburch.logisim.verilog.std.macrocomponents.ComposeCtx;

import java.util.HashMap;
import java.util.Map;

import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.parseIntRelaxed;
import static com.cburch.logisim.verilog.std.adapters.wordlvl.UnaryOpAdapter.guessUnaryWidth;

/** Registro global/simple de recetas por typeId de Yosys. */
public final class MacroRegistry {

    public interface Recipe {
        InstanceHandle build(ComposeCtx ctx, VerilogCell cell, Location where) throws CircuitException;
    }

    private final Map<String, Recipe> map = new HashMap<>();

    public void register(String yosysTypeId, Recipe r){ map.put(yosysTypeId, r); }
    public Recipe find(String yosysTypeId){ return map.get(yosysTypeId); }

    /** Bootstrap con unarias. */
    public static MacroRegistry bootUnaryDefaults() {
        MacroRegistry reg = new MacroRegistry();
        UnaryOpComposer u = new UnaryOpComposer();

        reg.register("$reduce_or", (ctx, cell, where) -> {
            int w = guessUnaryWidth(cell.params());
            return u.buildReduceOrAsSubckt(ctx, cell, where, w);
        });
        reg.register("$reduce_bool", (ctx, cell, where) -> {
            int w = guessUnaryWidth(cell.params());
            return u.buildReduceOrAsSubckt(ctx, cell, where, w);
        });
        reg.register("$reduce_and", (ctx, cell, where) -> {
            int w = guessUnaryWidth(cell.params());
            return u.buildReduceAndAsSubckt(ctx, cell, where, w);
        });
        reg.register("$reduce_xor", (ctx, cell, where) -> {
            int w = guessUnaryWidth(cell.params());
            return u.buildReduceXorAsSubckt(ctx, cell, where, w, true);
        });
        reg.register("$reduce_xnor", (ctx, cell, where) -> {
            int w = guessUnaryWidth(cell.params());
            return u.buildReduceXorAsSubckt(ctx, cell, where, w, false);
        });
        return reg;
    }

    public static MacroRegistry bootBinaryDefaults() {
        MacroRegistry reg = new MacroRegistry();
        BinaryOpComposer b = new BinaryOpComposer();

        reg.register("$ne", (ctx, cell, where) -> {
            int aw = guessWidth(cell.params(), "A_WIDTH", 1);
            int bw = guessWidth(cell.params(), "B_WIDTH", 1);
            return b.buildNeAsSubckt(ctx, cell, where, aw, bw);
        });

        reg.register("$le", (ctx, cell, where) -> {
            int aw = guessWidth(cell.params(), "A_WIDTH", 1);
            int bw = guessWidth(cell.params(), "B_WIDTH", 1);
            return b.buildLeAsSubckt(ctx, cell, where, aw, bw);
        });

        reg.register("$ge", (ctx, cell, where) -> {
            int aw = guessWidth(cell.params(), "A_WIDTH", 1);
            int bw = guessWidth(cell.params(), "B_WIDTH", 1);
            return b.buildGeAsSubckt(ctx, cell, where, aw, bw);
        });

        return reg;
    }

    // Helper local (igual que usas en otros lugares)
    private static int guessWidth(CellParams p, String key, int dflt) {
        if (p instanceof GenericCellParams g) {
            return Math.max(1, parseIntRelaxed(g.asMap().get(key), dflt));
        }
        return dflt;
    }
}
