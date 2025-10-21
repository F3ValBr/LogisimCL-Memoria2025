package com.cburch.logisim.verilog.comp.factories.wordlvl;

import com.cburch.logisim.verilog.comp.AbstractVerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.VerilogCellFactory;
import com.cburch.logisim.verilog.comp.impl.WordLvlCellImpl;
import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.specs.RegisterAttribs;
import com.cburch.logisim.verilog.comp.specs.wordlvl.RegisterOp;
import com.cburch.logisim.verilog.comp.specs.wordlvl.RegisterOpParams;
import com.cburch.logisim.verilog.comp.specs.wordlvl.registerparams.*;
import com.cburch.logisim.verilog.comp.specs.wordlvl.registerparams.dffeparams.*;

import java.util.*;

/**
 * Factory for creating register operation Verilog cells.
 * Supports various register operations like DFF, DFFE, SDFF, etc.
 */
public class RegisterOpFactory extends AbstractVerilogCellFactory implements VerilogCellFactory {
    @Override
    public VerilogCell create(String name, String type,
                              Map<String,String> parameters,
                              Map<String,Object> attributes,
                              Map<String,String> portDirections,
                              Map<String, List<Object>> connections) {

        RegisterOp op = RegisterOp.fromYosys(type);

        RegisterOpParams params = getRegisterOpParams(op, parameters);

        var attribs = new RegisterAttribs(attributes);
        VerilogCell cell = new WordLvlCellImpl(name, CellType.fromYosys(type), params, attribs);

        buildEndpoints(cell, portDirections, connections);

        // -------- Common validations --------
        int w = params.width();
        switch (op) {
            case SDFF, SDFFE, SDFFCE -> {
                requirePortWidth(cell, "CLK",  1);
                requirePortWidth(cell, "SRST", 1);
                requirePortWidth(cell, "D",    w);
                requirePortWidth(cell, "Q",    w);
            }
        }

        // -------- Specific validations --------
        if (op == RegisterOp.SDFFE) {
            // EN must exists and be 1 bit.
            // In some dumps it appears as CE instead of EN.
            if (hasPort(cell, "EN"))      requirePortWidth(cell, "EN", 1);
            else if (hasPort(cell, "CE")) requirePortWidth(cell, "CE", 1);
            else throw new IllegalStateException(cell.name()+": SDFFE requires port EN (or CE) of 1 bit");
        }

        if (op == RegisterOp.SDFFCE) {
            if (hasPort(cell, "CE"))      requirePortWidth(cell, "CE", 1);
            else if (hasPort(cell, "EN")) requirePortWidth(cell, "EN", 1);
            else throw new IllegalStateException(cell.name()+": SDFFCE requires port CE (or EN) of 1 bit");
        }

        return cell;
    }

    private static RegisterOpParams getRegisterOpParams(RegisterOp op, Map<String,String> parameters) {
        return switch (op) {
            // base
            case DFF    -> new DFFParams(parameters);
            case ADFF   -> new ADFFParams(parameters);
            case ALDFF  -> new AlDFFParams(parameters);
            case DFFSR  -> new DFFSRParams(parameters);
            case SDFF   -> new SDFFParams(parameters);

            // + enable
            case DFFE   -> new DFFEParams(parameters);
            case ADFFE  -> new ADFFEParams(parameters);
            case ALDFFE -> new AlDFFEParams(parameters);
            case DFFSRE -> new DFFSREParams(parameters);
            case SDFFE  -> new SDFFEParams(parameters);
            case SDFFCE -> new SDFFCEParams(parameters);

            default     -> new GenericRegisterParams(parameters); // fallback
        };
    }

    private static void requirePortWidth(VerilogCell cell, String port, int expected) {
        int got = cell.portWidth(port);
        if (got != expected) {
            throw new IllegalStateException(cell.name() + ": port " + port +
                    " width mismatch. expected=" + expected + " got=" + got);
        }
    }

    private static boolean hasPort(VerilogCell cell, String port) {
        return cell.getPortNames().contains(port);
    }
}
