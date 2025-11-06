package com.cburch.logisim.verilog.comp.specs.gatelvl;

import com.cburch.logisim.verilog.comp.specs.GenericCellParams;

import java.util.Map;

public final class GateOpParams extends GenericCellParams {
    private final GateOp op;

    public GateOpParams(GateOp op, Map<String,?> raw) {
        super(raw);
        this.op = op;
    }

    // --- Getters ---
    public GateOp op() { return op; }
    public int width() { return 1; }
    public int numOfInputs() {
        int trailingNumber = extractTrailingNumber(op.yosysId());
        return trailingNumber > 0 ? trailingNumber : -1;
    }
    public int sWidth() {
        int n = extractTrailingNumber(op.yosysId());
        if (n <= 1) return 1;
        return (int) Math.round(Math.log(n) / Math.log(2));
    }

    public static int extractTrailingNumber(String name) {
        if (name == null || name.isEmpty()) return -1;

        // 1) Elimina posibles prefijos/sufijos como "$" o "_"
        int lastUnderscore = name.lastIndexOf('_');
        if (lastUnderscore <= 0) return -1;

        // 2) Busca hacia atrás desde el último '_' hasta el primer dígito antes de él
        int end = lastUnderscore - 1;
        int start = end;
        while (start >= 0 && Character.isDigit(name.charAt(start))) start--;

        if (end <= start) return -1; // no había dígitos antes del último '_'

        try {
            return Integer.parseInt(name.substring(start + 1, end + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
