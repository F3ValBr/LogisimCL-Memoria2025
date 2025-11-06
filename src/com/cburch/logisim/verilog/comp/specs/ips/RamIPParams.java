package com.cburch.logisim.verilog.comp.specs.ips;

import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;

import java.util.Map;

import static com.cburch.logisim.verilog.std.AbstractComponentAdapter.parseIntRelaxed;

public final class RamIPParams extends GenericCellParams {
    final int width;     // DATA bits
    final int abits;     // ADDR bits
    final boolean hasClk;
    final boolean hasWe;

    private RamIPParams(int width, int abits, boolean hasClk, boolean hasWe) {
        this.width = Math.max(1, width);
        this.abits = Math.max(1, abits);
        this.hasClk = hasClk;
        this.hasWe  = hasWe;
    }

    public static RamIPParams from(Map<String, String> raw) {
        int w = parseIntRelaxed(raw.get("DATA_SZ"), 1);
        int a = parseIntRelaxed(raw.get("ADDR_SZ"), 1);
        boolean clk = parseBool(raw.getOrDefault("HAS_CLK","1"));
        boolean we  = parseBool(raw.getOrDefault("HAS_WE","1"));
        return new RamIPParams(w, a, clk, we);
    }

    public static void validatePorts(VerilogCell c, RamIPParams p) {
        // nombres genéricos esperados: CLK, WE, A, D, Q (puedes adaptarlos a tu JSON)
        //mustHave(c, "A", p.abits);
        //mustHave(c, "D", p.width);
        //mustHave(c, "Q", p.width);
        //if (p.hasClk) mustHave(c, "CLK", 1);
        //if (p.hasWe)  mustHave(c, "WE",  1);
    }

    private static void mustHave(VerilogCell c, String port, int w) {
        if (!c.getPortNames().contains(port) || c.portWidth(port) != w) {
            throw new IllegalStateException(c.name()+": port "+port+" mismatch (expected "+w+")");
        }
    }
    private static int parseInt(String s, int d) { try { return Integer.parseInt(zeroTrim(s)); } catch(Exception e){return d;} }
    private static boolean parseBool(String s) { String t = zeroTrim(s); return "1".equals(t) || "true".equalsIgnoreCase(t); }
    private static String zeroTrim(String s){ return s==null? "": s.replaceAll("^0+(?!$)",""); }
}

