package com.cburch.logisim.verilog.comp.specs.gatelvl;

import com.cburch.logisim.verilog.comp.specs.GenericCellParams;

public final class RegisterGateOpParams extends GenericCellParams {
    private final RegisterGateUtils.RegGateConfig cfg;
    public RegisterGateOpParams(RegisterGateUtils.RegGateConfig cfg) { this.cfg = cfg; }

    public RegisterGateUtils.RegGateConfig cfg() { return cfg; }

    @Override public String toString() { return "RegisterGateOpParams{" + cfg + "}"; }
}
