package com.cburch.logisim.verilog.comp.specs.gatelvl;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

public enum GateOp {
    // Simples (1-bit)
    AND    ("$_AND_",   Category.SIMPLE),
    BUF    ("$_BUF_",   Category.SIMPLE),
    NAND   ("$_NAND_",  Category.SIMPLE),
    NOR    ("$_NOR_",   Category.SIMPLE),
    NOT    ("$_NOT_",   Category.SIMPLE),
    OR     ("$_OR_",    Category.SIMPLE),
    XNOR   ("$_XNOR_",  Category.SIMPLE),
    XOR    ("$_XOR_",   Category.SIMPLE),

    // Combinados
    ANDNOT ("$_ANDNOT_",Category.COMBINED),
    ORNOT  ("$_ORNOT_", Category.COMBINED),
    AOI3   ("$_AOI3_",  Category.COMBINED),
    AOI4   ("$_AOI4_",  Category.COMBINED),
    OAI3   ("$_OAI3_",  Category.COMBINED),
    OAI4   ("$_OAI4_",  Category.COMBINED),

    // Multiplexores anchos / variantes
    MUX    ("$_MUX_",   Category.MUX_FAMILY),
    NMUX   ("$_NMUX_",  Category.MUX_FAMILY),
    MUX4   ("$_MUX4_",  Category.MUX_FAMILY),
    MUX8   ("$_MUX8_",  Category.MUX_FAMILY),
    MUX16  ("$_MUX16_", Category.MUX_FAMILY);

    public enum Category { SIMPLE, COMBINED, MUX_FAMILY }

    private final String yosysId;
    private final Category category;

    GateOp(String yosysId, Category category) {
        this.yosysId = yosysId;
        this.category = category;
    }

    public String yosysId() { return yosysId; }
    public Category category() { return category; }

    public boolean isSimple()    { return category == Category.SIMPLE; }
    public boolean isCombined()  { return category == Category.COMBINED; }
    public boolean isMuxFamily() { return category == Category.MUX_FAMILY; }

    // ------- Índice estático -------
    private static final Map<String, GateOp> INDEX;
    static {
        Map<String, GateOp> m = new HashMap<>();
        for (GateOp op : values()) m.put(op.yosysId, op);
        INDEX = Collections.unmodifiableMap(m);
    }

    public static boolean isGateTypeId(String typeId) {
        return INDEX.containsKey(typeId);
    }

    public static GateOp fromYosys(String typeId) {
        GateOp op = INDEX.get(typeId);
        if (op == null) throw new IllegalArgumentException("Unknown gate op: " + typeId);
        return op;
    }

    public static Optional<GateOp> tryFromYosys(String typeId) {
        return Optional.ofNullable(INDEX.get(typeId));
    }
}
