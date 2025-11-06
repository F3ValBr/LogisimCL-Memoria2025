package com.cburch.logisim.verilog.std.adapters.gatelvl;


import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.*;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.instance.PortGeom;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;
import com.cburch.logisim.verilog.comp.auxiliary.SupportsFactoryLookup;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.gatelvl.RegisterGateOp;
import com.cburch.logisim.verilog.comp.specs.gatelvl.RegisterGateOpParams;
import com.cburch.logisim.verilog.comp.specs.gatelvl.RegisterGateUtils;
import com.cburch.logisim.verilog.std.AbstractComponentAdapter;
import com.cburch.logisim.verilog.std.BuiltinPortMaps;
import com.cburch.logisim.verilog.std.InstanceHandle;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.*;
import java.util.Map;

public final class RegisterGateOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    // === Nombres/valores de atributos del Register (de tu Register.java) ===
    private static final String A_REG_HAS_EN       = "regHasEnable";
    private static final String A_EN_POLARITY      = "enablePolarity";
    private static final String A_RESET_TYPE       = "resetType";
    private static final String A_RESET_POLARITY   = "resetPolarity";
    private static final String A_RESET_VALUE      = "resetValue";

    private static final String OPT_EN_ACTIVE_HIGH = "enActiveHigh";
    private static final String OPT_EN_ACTIVE_LOW  = "enActiveLow";

    private static final String OPT_RST_ASYNC      = "asyncReset";
    private static final String OPT_RST_SYNC       = "syncReset";
    private static final String OPT_RST_NONE       = "noReset";

    private static final String OPT_RST_ACTIVE_HIGH= "rstActiveHigh";
    private static final String OPT_RST_ACTIVE_LOW = "rstActiveLow";

    private final ModuleBlackBoxAdapter fallback = new ModuleBlackBoxAdapter();

    private record LibFactory(Library lib, ComponentFactory factory) { }

    @Override
    public boolean accepts(CellType t) {
        if (t == null) return false;
        String id = safeTypeId(t);
        if (id == null) return false;
        String norm = id.toUpperCase(java.util.Locale.ROOT);
        return RegisterGateOp.matchesRGOp(norm);
    }

    private static String safeTypeId(CellType t) {
        try { return t.typeId(); } catch (Throwable ignore) { return null; }
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        LibFactory rf = pickRegisterFactory(proj);
        if (rf == null) return fallback.create(proj, circ, g, cell, where);

        final String typeId      = cell.type().typeId();
        final String typeIdUpper = typeId.toUpperCase(java.util.Locale.ROOT);

        // 0) SR latch puro: por ahora fallback
        if (typeIdUpper.equals("$_SR_")) {
            return fallback.create(proj, circ, g, cell, where);
        }

        // 1) Ancho: 1 por ser Gate level
        final int width = 1;

        // 2) Obtener cfg desde params (NO volver a parsear el typeId)
        RegisterGateUtils.RegGateConfig cfg = null;
        if (cell.params() instanceof RegisterGateOpParams rp) {
            cfg = rp.cfg();
        }
        // si por alguna razón no vino en params, último recurso: parsear
        if (cfg == null) {
            cfg = RegisterGateUtils.FFNameParser.parse(typeIdUpper);
            if (cfg == null) return fallback.create(proj, circ, g, cell, where);
        }

        // ¿Es latch?
        final boolean isLatch = cfg.base().isLatch() || typeIdUpper.startsWith("$_DLATCH");

        // Clock edge (ignorado si es latch)
        final boolean clkRising = cfg.clkPosEdge();

        // Enable: los latches usan enable como trigger de nivel
        final boolean hasEnable  = cfg.hasEnable() || isLatch;
        final boolean enActiveHi = !hasEnable || (cfg.enablePol() == RegisterGateUtils.Pol.POS);

        // Reset/Set → decide valor y polaridad
        boolean hasReset = cfg.hasReset() || cfg.hasSet();
        String  rstVal   = "0";
        boolean rstHigh  = true;

        if (cfg.hasReset()) {
            rstVal = "0";
            rstHigh = (cfg.resetPol() == RegisterGateUtils.Pol.POS);
        } else if (cfg.hasSet()) {
            rstVal = "1";
            rstHigh = (cfg.setPol() == RegisterGateUtils.Pol.POS);
        }

        if (cfg.initValue() == 0) rstVal = "0";
        else if (cfg.initValue() == 1) rstVal = "1";

        // Fam. con reset SINCRÓNICO (SDFF*, SDFFE*, SDFFCE*)
        final boolean isSyncResetFamily = cfg.base().isSyncResetFamily();
        final String RESET_TYPE_OPT =
                !hasReset ? OPT_RST_NONE
                        : (isSyncResetFamily ? OPT_RST_SYNC : OPT_RST_ASYNC);

        try {
            AttributeSet attrs = rf.factory.createAttributeSet();
            safeSet(attrs, StdAttr.WIDTH, BitWidth.create(width));
            safeSet(attrs, StdAttr.LABEL, cleanCellName(cell.name()));

            // Trigger
            if (isLatch) {
                safeSet(attrs, StdAttr.TRIGGER, enActiveHi ? StdAttr.TRIG_HIGH : StdAttr.TRIG_LOW);
            } else {
                safeSet(attrs, StdAttr.TRIGGER, clkRising ? StdAttr.TRIG_RISING : StdAttr.TRIG_FALLING);
            }

            // Enable
            setBooleanByName(attrs, A_REG_HAS_EN, hasEnable);
            if (hasEnable) {
                setOptionByName(attrs, A_EN_POLARITY, enActiveHi ? OPT_EN_ACTIVE_HIGH : OPT_EN_ACTIVE_LOW);
            }

            // Reset
            setOptionByName(attrs, A_RESET_TYPE, RESET_TYPE_OPT);
            if (hasReset) {
                setOptionByName(attrs, A_RESET_POLARITY, rstHigh ? OPT_RST_ACTIVE_HIGH : OPT_RST_ACTIVE_LOW);
                setStringByName(attrs, A_RESET_VALUE, rstVal);
            }

            Component comp = addComponent(proj, circ, g, rf.factory, where, attrs);
            Map<String,Integer> nameToIdx = BuiltinPortMaps.forFactory(rf.lib, rf.factory, comp);
            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir Register para " + typeId + ": " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        LibFactory rf = pickRegisterFactory(proj);
        return rf == null ? null : rf.factory;
    }

    /* ===================== helpers ===================== */

    private static LibFactory pickRegisterFactory(Project proj) {
        LogisimFile f = proj.getLogisimFile();
        if (f == null) return null;
        Library mem = f.getLibrary("Memory");
        if (mem == null) return null;
        ComponentFactory cf = FactoryLookup.findFactory(mem, "Register");
        return (cf == null) ? null : new LibFactory(mem, cf);
    }

    static <T> void safeSet(AttributeSet attrs, Attribute<T> attr, T val) {
        try { attrs.setValue(attr, val); } catch (Exception ignore) { }
    }
}
