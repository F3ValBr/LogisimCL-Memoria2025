package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.data.*;
import com.cburch.logisim.verilog.comp.auxiliary.*;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.BitRef;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.Const0;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.file.ui.WarningCollector;
import com.cburch.logisim.verilog.std.adapters.wordlvl.*;

import java.util.*;

final class SpecBuilder {
    private final WarningCollector warn;

    /** Creates a new specification builder.
     * @param w Warning collector to use for reporting issues.
     */
    SpecBuilder(WarningCollector w) { this.warn = w; }

    /** Direction of a port.*/
    enum Dir { IN, OUT, INOUT }

    /** Determines the direction of a port in a cell, based on its endpoints.
     * If the port has both input and output endpoints, it is INOUT.
     * If it has only output endpoints, it is OUT.
     * If it has only input endpoints, it is IN.
     * If it has no endpoints, it is IN (default).
     * @param cell Cell to analyze.
     * @param portName Port name to analyze.
     * @return Direction of the port.
     */
    static Dir dirForPort(VerilogCell cell, String portName) {
        boolean seenIn=false, seenOut=false;
        for (PortEndpoint ep : cell.endpoints()) {
            if (!portName.equals(ep.getPortName())) continue;
            switch (ep.getDirection()) {
                case INPUT -> seenIn = true;
                case OUTPUT -> seenOut = true;
                case INOUT -> { return Dir.INOUT; }
            }
        }
        if (seenIn && seenOut) return Dir.INOUT;
        if (seenOut) return Dir.OUT;
        return Dir.IN;
    }

    /** Result of constant bits analysis. */
    record ConstAnalysis(boolean allPresent, boolean all01, int acc) { }

    /** Analyzes the constant bits of a port, given its endpoints by index.
     * @param byIdx Array of endpoints by bit index.
     * @return Analysis result indicating if all bits are present, if all are 0/1, and the accumulated value.
     */
    static ConstAnalysis analyzeConstBits(PortEndpoint[] byIdx) {
        boolean allPresent = true, all01 = true;
        int acc = 0;
        for (int i = 0; i < byIdx.length; i++) {
            PortEndpoint ep = byIdx[i];
            if (ep == null) { allPresent = false; all01 = false; break; }
            BitRef br = ep.getBitRef();
            if (br instanceof Const0) { /* 0 */ }
            else {
                String n = (br == null) ? "" : br.getClass().getSimpleName();
                if ("Const1".equals(n)) acc |= (1 << i);
                else if ("ConstX".equals(n) || "ConstZ".equals(n)) all01 = false;
                else all01 = false; // Net
            }
        }
        return new ConstAnalysis(allPresent, all01, acc);
    }

    /** Builds bit specifications for a cell port.
     * Each bit specification can be "0", "1", "x" (unknown), or "N{netId}".
     * @param moduleName Name of the module containing the cell (for warnings).
     * @param cell Cell containing the port.
     * @param port Port name to analyze.
     * @return List of bit specifications for the port.
     */
    List<String> buildBitSpecsForCellPort(String moduleName, VerilogCell cell, String port) {
        int w = Math.max(1, cell.portWidth(port));
        PortEndpoint[] byIdx = new PortEndpoint[w];
        for (PortEndpoint ep : cell.endpoints()) {
            if (!port.equals(ep.getPortName())) continue;
            int i = ep.getBitIndex();
            if (i >= 0 && i < w) byIdx[i] = ep;
        }
        List<String> specs = new ArrayList<>(w);
        for (int i = 0; i < w; i++) {
            PortEndpoint ep = byIdx[i];
            if (ep == null) { specs.add("x"); warn.addXBit(moduleName+" :: "+cell.name(), port, i, "bit sin endpoint"); continue; }
            BitRef br = ep.getBitRef();
            if (br instanceof Const0) { specs.add("0"); continue; }
            String n = (br == null) ? "" : br.getClass().getSimpleName();
            if ("Const1".equals(n)) { specs.add("1"); continue; }
            if ("ConstX".equals(n) || "ConstZ".equals(n)) { specs.add("x"); warn.addXBit(moduleName+" :: "+cell.name(), port, i, "const X/Z"); continue; }
            Integer nid = ep.getNetIdOrNull();
            if (nid == null) { specs.add("x"); warn.addXBit(moduleName+" :: "+cell.name(), port, i, "sin netId"); }
            else specs.add("N"+nid);
        }
        return specs;
    }

    /** Builds bit specifications for a top-level module port.
     * Each bit specification can be "0", "1", "x" (unknown), or "N{netId}".
     * @param moduleName Name of the module (for warnings).
     * @param p Module port to analyze.
     * @return List of bit specifications for the port.
     */
    List<String> buildBitSpecsForTopPort(String moduleName, ModulePort p) {
        int w = Math.max(1, p.width());
        int[] arr = p.netIds();
        List<String> specs = new ArrayList<>(w);
        for (int i=0;i<w;i++) {
            int nid = (arr!=null && i<arr.length) ? arr[i] : ModulePort.CONST_X;
            switch (nid) {
                case ModulePort.CONST_0 -> specs.add("0");
                case ModulePort.CONST_1 -> specs.add("1");
                default -> {
                    if (nid >= 0) specs.add("N"+nid);
                    else { specs.add("x"); warn.addXBit(moduleName, p.name(), i, "top-port indeterminado"); }
                }
            }
        }
        return specs;
    }

    /** Creates a pretty label for a list of bit specifications.
     * Groups contiguous net IDs into ranges.
     * Examples:
     *   ["N3", "N4", "N5"] -> "N3-5"
     *   ["N1", "N3", "N4", "N6"] -> "N1;3-4;6"
     *   ["0", "1", "N2", "N3", "x"] -> "x"
     * @param specs List of bit specifications.
     * @return Pretty label string, or empty if no net IDs are present.
     */
    static String makePrettyLabel(List<String> specs) {
        boolean hasX = false;
        List<Integer> ordered = new ArrayList<>();

        if (specs != null) {
            for (String s : specs) {
                if (s == null) continue;
                String t = s.trim();
                if (t.equalsIgnoreCase("x") || t.equalsIgnoreCase("z")) {
                    hasX = true;       // cualquier X/Z fuerza etiqueta "x"
                    continue;
                }
                if (t.startsWith("N")) {
                    try {
                        ordered.add(Integer.parseInt(t.substring(1)));
                    } catch (Exception ignore) { /* ignora tokens mal formados */ }
                }
                // "0" o "1" no aportan a la etiqueta visible
            }
        }

        if (hasX) return "x";          // mezcla con nets o solo X ⇒ "x"
        if (ordered.isEmpty()) return ""; // solo constantes 0/1 (o vacío) ⇒ sin etiqueta

        List<int[]> ranges = contiguousRanges(ordered);
        StringBuilder sb = new StringBuilder("N");
        for (int i = 0; i < ranges.size(); i++) {
            int[] r = ranges.get(i);
            if (i > 0) sb.append(';');
            if (r[0] == r[1]) sb.append(r[0]);
            else sb.append(r[0]).append('-').append(r[1]);
        }
        return sb.toString();
    }

    /** Given a list of integers, finds contiguous ranges and returns them as a list of [start, end] pairs.
     * Example: [1,2,3,5,6,8] -> [[1,3],[5,6],[8,8]]
     * @param ordered List of integers (not necessarily sorted).
     * @return List of contiguous ranges as [start, end] pairs.
     */
    private static List<int[]> contiguousRanges(List<Integer> ordered) {
        List<int[]> out = new ArrayList<>();
        if (ordered.isEmpty()) return out;
        int start = ordered.get(0), prev = start;
        for (int i=1;i<ordered.size();i++) {
            int cur = ordered.get(i);
            if (cur == prev+1) prev = cur; else { out.add(new int[]{start, prev}); start = prev = cur; }
        }
        out.add(new int[]{start, prev});
        return out;
    }
}

