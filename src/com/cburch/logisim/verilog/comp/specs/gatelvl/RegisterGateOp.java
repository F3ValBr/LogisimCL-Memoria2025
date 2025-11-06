package com.cburch.logisim.verilog.comp.specs.gatelvl;

import java.util.Optional;

public enum RegisterGateOp {
    // === Flip-flops ===
    DFF    ("$_DFF_"),
    DFFE   ("$_DFFE_"),
    DFFSR  ("$_DFFSR_"),
    DFFSRE ("$_DFFSRE_"),
    SDFF   ("$_SDFF_"),
    SDFFE  ("$_SDFFE_"),
    SDFFCE ("$_SDFFCE_"),
    ALDFF  ("$_ALDFF_"),
    ALDFFE ("$_ALDFFE_"),

    // === Latches ===
    DLATCH  ("$_DLATCH_"),
    DLATCHSR("$_DLATCHSR_"),

    // === Genéricos ===
    FF     ("$_FF_"),
    SR     ("$_SR_");

    private final String prefix;
    RegisterGateOp(String prefix) { this.prefix = prefix; }
    public String prefix() { return prefix; }

    private static final RegisterGateOp[] ALL = values();

    public static boolean matchesRGOp(String typeId) {
        if (typeId == null) return false;
        for (var k : ALL) if (typeId.startsWith(k.prefix)) return true;
        return false;
    }

    public static RegisterGateOp fromYosys(String typeId) {
        for (var k : ALL) if (typeId.startsWith(k.prefix)) return k;
        throw new IllegalArgumentException("Unknown FF op: " + typeId);
    }

    public static Optional<RegisterGateOp> tryFromYosys(String typeId) {
        for (var k : ALL) if (typeId.startsWith(k.prefix)) return Optional.of(k);
        return Optional.empty();
    }

    /** @return true si este op representa un latch (nivel-sensible) */
    public boolean isLatch() {
        return this == DLATCH || this == DLATCHSR;
    }

    /** @return true si este op representa un flip-flop (flanco-sensible) */
    public boolean isFlop() {
        return !isLatch();
    }

    /** @return true si este op usa reset síncrono (familias SDFF*) */
    public boolean isSyncResetFamily() {
        return this == SDFF || this == SDFFE || this == SDFFCE;
    }

    /** @return true si este op incluye reset asíncrono (familias ADFF, ALDFF, DFFSR, etc.) */
    public boolean isAsyncResetFamily() {
        return this == ALDFF || this == ALDFFE || this == DFFSR || this == DFFSRE || this == DLATCHSR;
    }
}
