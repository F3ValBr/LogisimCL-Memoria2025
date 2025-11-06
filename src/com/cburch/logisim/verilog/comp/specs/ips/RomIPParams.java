package com.cburch.logisim.verilog.comp.specs.ips;

import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.specs.GenericCellParams;

import java.util.Map;

public final class RomIPParams extends GenericCellParams {
    final int width;
    final int abits;

    private RomIPParams(int width, int abits) {
        this.width = Math.max(1, width);
        this.abits = Math.max(1, abits);
    }
    public static RomIPParams from(Map<String, String> raw) {
        int w = parseInt(raw.get("DATA_SZ"), 1);
        int a = parseInt(raw.get("ADDR_SZ"), 1);
        return new RomIPParams(w, a);
    }
    public static void validatePorts(VerilogCell c, RomIPParams p) {
        //mustHave(c, "A", p.abits);
        //mustHave(c, "Q", p.width);
        // ROM puede tener EN opcional
        //if (c.getPortNames().contains("EN")) mustHave(c,"EN",1);
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
