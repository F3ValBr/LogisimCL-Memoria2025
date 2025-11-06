package com.cburch.logisim.verilog.std.adapters.wordlvl;

// RegisterOpAdapter.java

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitException;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.data.*;
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
import com.cburch.logisim.verilog.std.*;
import com.cburch.logisim.verilog.std.adapters.ModuleBlackBoxAdapter;

import java.awt.Graphics;
import java.util.Map;

public final class RegisterOpAdapter extends AbstractComponentAdapter
        implements SupportsFactoryLookup {

    // Names esperados en tu Register (cámbialos si usaste otros):
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

    // Pareja (Library, ComponentFactory) para usar BuiltinPortMaps.forFactory(...)
    private record LibFactory(Library lib, ComponentFactory factory) { }

    @Override
    public boolean accepts(CellType t) {
        return t != null && t.isWordLevel() && t.isRegister();
    }

    @Override
    public InstanceHandle create(Project proj, Circuit circ, Graphics g, VerilogCell cell, Location where) {
        LibFactory lf = pickRegisterFactory(proj);
        if (lf == null) {
            return fallback.create(proj, circ, g, cell, where);
        }

        final CellParams params = cell.params();
        final String typeId = cell.type().typeId().toLowerCase(java.util.Locale.ROOT);

        final int width = Math.max(1, guessWidth(params));

        // ¿Es latch?
        final boolean isLatch =
                typeId.contains("dlatch") || typeId.startsWith("$dlatch");

        // Enable (Yosys usa EN/EN_POLARITY en varios tipos)
        final boolean hasEnPort =
                isLatch || hasPort(cell, "EN") ||
                        typeId.contains("dffe") || typeId.contains("sdffe") ||
                        typeId.contains("aldffe") || typeId.contains("adffe") ||
                        typeId.contains("dffsre") || typeId.contains("sdffce");

        final boolean enActiveHigh = readBitDefault(params, "EN_POLARITY", true);

        // Para flip-flops: CLK_POLARITY
        final boolean clkRising = readBitDefault(params, "CLK_POLARITY", true);

        // Reset (heurística común)
        final ResetInfo rst = detectReset(cell);

        try {
            AttributeSet attrs = lf.factory.createAttributeSet();

            // Básicos
            safeSet(attrs, StdAttr.WIDTH, BitWidth.create(width));
            safeSet(attrs, StdAttr.LABEL, cleanCellName(cell.name()));

            // Trigger:
            //  - Latch: TRIG_HIGH / TRIG_LOW según EN_POLARITY
            //  - FF:    TRIG_RISING / TRIG_FALLING según CLK_POLARITY
            if (isLatch) {
                safeSet(attrs, StdAttr.TRIGGER, enActiveHigh ? StdAttr.TRIG_HIGH : StdAttr.TRIG_LOW);
            } else {
                safeSet(attrs, StdAttr.TRIGGER, clkRising ? StdAttr.TRIG_RISING : StdAttr.TRIG_FALLING);
            }

            // Enable visible + polaridad
            setBooleanByName(attrs, A_REG_HAS_EN, hasEnPort || isLatch);
            if (hasEnPort || isLatch) {
                setOptionByName(attrs, A_EN_POLARITY,
                        enActiveHigh ? OPT_EN_ACTIVE_HIGH : OPT_EN_ACTIVE_LOW);
            }

            // Reset por tipo detectado
            switch (rst.kind) {
                case NONE -> setOptionByName(attrs, A_RESET_TYPE, OPT_RST_NONE);
                case ASYNC -> {
                    setOptionByName(attrs, A_RESET_TYPE, OPT_RST_ASYNC);
                    setOptionByName(attrs, A_RESET_POLARITY, rst.activeHigh ? OPT_RST_ACTIVE_HIGH : OPT_RST_ACTIVE_LOW);
                    setStringByName(attrs, A_RESET_VALUE, rst.valueText);
                }
                case SYNC -> {
                    setOptionByName(attrs, A_RESET_TYPE, OPT_RST_SYNC);
                    setOptionByName(attrs, A_RESET_POLARITY, rst.activeHigh ? OPT_RST_ACTIVE_HIGH : OPT_RST_ACTIVE_LOW);
                    setStringByName(attrs, A_RESET_VALUE, rst.valueText);
                }
            }

            // Nota sobre variantes:
            // - $adlatch → normalmente caerá en ASYNC vía detectReset (ARST_*).
            // - $dlatchsr → si Yosys expone SRST/ARST lo tomará; si no, queda NO_RESET.
            // - $sr (latch SR puro): por ahora fallback (no tiene clock/enable estándar).
            if (typeId.equals("$sr")) {
                // TODO: mapear $sr a Register más adelante, cambiar esto.
                return fallback.create(proj, circ, g, cell, where);
            }

            Component comp = addComponent(proj, circ, g, lf.factory, where, attrs);

            // == Port map dinámico por librería+factory+instancia ==
            Map<String,Integer> nameToIdx =
                    BuiltinPortMaps.forFactory(lf.lib, lf.factory, comp);

            if (nameToIdx.isEmpty()) {
                // Orden por tu Register: OUT=0, IN=1, CK=2, (RST=?, EN=?)
                nameToIdx = new java.util.LinkedHashMap<>();
                nameToIdx.put("Q",   0);
                nameToIdx.put("D",   1);
                nameToIdx.put("CLK", 2);
            }

            PortGeom pg = PortGeom.of(comp, nameToIdx);
            return new InstanceHandle(comp, pg);
        } catch (CircuitException e) {
            throw new IllegalStateException("No se pudo añadir Register/Latch: " + e.getMessage(), e);
        }
    }

    @Override
    public ComponentFactory peekFactory(Project proj, VerilogCell cell) {
        LibFactory lf = pickRegisterFactory(proj);
        return lf == null ? null : lf.factory;
    }

    /* ================= helpers ================= */

    private static LibFactory pickRegisterFactory(Project proj) {
        LogisimFile lf = proj.getLogisimFile();
        if (lf == null) return null;
        Library mem = lf.getLibrary("Memory");
        if (mem == null) return null;
        ComponentFactory f = FactoryLookup.findFactory(mem, "Register");
        return (f == null) ? null : new LibFactory(mem, f);
    }

    private static int guessWidth(CellParams p) {
        if (p instanceof GenericCellParams g) {
            Object w = g.asMap().get("WIDTH");
            return parseIntRelaxed(w, 1);
        }
        return 1;
    }

    private static boolean hasPort(VerilogCell cell, String name) {
        for (String p : cell.getPortNames()) {
            if (p.equals(name)) return true;
        }
        return false;
    }

    /** Detecta tipo/polaridad/valor de reset a partir de typeId y parámetros Yosys. */
    private static ResetInfo detectReset(VerilogCell cell) {
        Map<String, Object> m = (cell.params() instanceof GenericCellParams g) ? g.asMap() : java.util.Map.of();
        String t = cell.type().typeId().toLowerCase(java.util.Locale.ROOT);

        // Heurística por presencia de parámetros
        boolean hasArst = m.containsKey("ARST_VALUE") || m.containsKey("ARST_POLARITY");
        boolean hasSrst = m.containsKey("SRST_VALUE") || m.containsKey("SRST_POLARITY");

        if (t.contains("adff") || t.contains("aldff") || t.contains("adffe") || t.contains("aldffe") || hasArst) {
            boolean pol = readBitDefault(cell.params(), "ARST_POLARITY", true);
            String val  = stringDefault(m.get("ARST_VALUE"), "0");
            return ResetInfo.async(pol, val);
        }
        if (t.contains("sdff") || t.contains("sdffe") || t.contains("sdffce") || hasSrst) {
            boolean pol = readBitDefault(cell.params(), "SRST_POLARITY", true);
            String val  = stringDefault(m.get("SRST_VALUE"), "0");
            return ResetInfo.sync(pol, val);
        }
        // TODO: $dffsr / $dffsre: modela como async por simplicidad (ajusta si necesitas)
        if (t.contains("dffsr") || t.contains("dffsre")) {
            // si hay SRST/ARST_* en params, respétalos
            return ResetInfo.none();
        }
        return ResetInfo.none();
    }

    /* ===== utilidades comunes ===== */

    static <T> void safeSet(AttributeSet attrs, Attribute<T> attr, T val) {
        try { attrs.setValue(attr, val); } catch (Exception ignore) { }
    }

    static boolean readBitDefault(CellParams p, String key, boolean def) {
        if (!(p instanceof GenericCellParams g)) return def;
        Object v = g.asMap().get(key);
        if (v == null) return def;
        int i = parseIntRelaxed(v, def ? 1 : 0);
        return i != 0;
    }

    static String stringDefault(Object v, String def) {
        if (v == null) return def;
        String s = String.valueOf(v).trim();
        return s.isEmpty() ? def : s;
    }

    /* ===== modelo simple de reset ===== */

    private static final class ResetInfo {
        enum Kind { NONE, ASYNC, SYNC }
        final Kind kind;
        final boolean activeHigh;
        final String valueText;

        private ResetInfo(Kind kind, boolean activeHigh, String valueText) {
            this.kind = kind; this.activeHigh = activeHigh; this.valueText = valueText;
        }
        static ResetInfo none()                     { return new ResetInfo(Kind.NONE,  true, "0"); }
        static ResetInfo async(boolean hi, String v){ return new ResetInfo(Kind.ASYNC, hi, v); }
        static ResetInfo sync (boolean hi, String v){ return new ResetInfo(Kind.SYNC,  hi, v); }
    }
}
