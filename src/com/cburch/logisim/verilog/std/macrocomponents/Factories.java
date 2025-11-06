package com.cburch.logisim.verilog.std.macrocomponents;

import com.cburch.logisim.comp.ComponentFactory;
import com.cburch.logisim.file.LogisimFile;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.tools.Library;
import com.cburch.logisim.verilog.comp.auxiliary.FactoryLookup;

/** Cache de factories (cárgalo una vez por Project). */
public final class Factories {
    public final ComponentFactory cmpF;         // Arithmetic → Comparator
    public final ComponentFactory muxF;         // Plexers    → Multiplexer
    public final ComponentFactory constF;      // Wiring    → Constant
    public final ComponentFactory pinF;        // Wiring    → Pin
    public final ComponentFactory bitExtendF;  // Wiring    → Bit Extender
    public final ComponentFactory notF;        // Gates     → NOT Gate
    public final ComponentFactory andF;        // Gates     → AND Gate
    public final ComponentFactory orF;         // Gates     → OR Gate
    public final ComponentFactory oddParityF;  // Gates     → Odd Parity
    public final ComponentFactory evenParityF; // Gates     → Even Parity

    private Factories(ComponentFactory cmpF,
                      ComponentFactory muxF,
                      ComponentFactory constF, ComponentFactory bitExtendF,
                      ComponentFactory notF,
                      ComponentFactory andF, ComponentFactory orF,
                      ComponentFactory oddP, ComponentFactory evenP,
                      ComponentFactory pinF) {
        this.cmpF = cmpF;
        this.muxF = muxF;
        this.constF = constF;
        this.bitExtendF = bitExtendF;
        this.notF = notF;
        this.andF = andF;
        this.orF = orF;
        this.oddParityF = oddP;
        this.evenParityF = evenP;
        this.pinF = pinF;
    }

    public static Factories warmup(Project proj) {
        LogisimFile lf = proj.getLogisimFile();
        // Libs (con fallback por si cambian etiquetas en forks)
        Library arithmetic = getLib(lf, "Arithmetic");
        Library wiring     = getLib(lf, "Wiring");
        Library gates      = getLib(lf, "Gates");
        Library plexers    = getLib(lf, "Plexers");

        ComponentFactory cmp        = find(arithmetic, "Comparator");
        ComponentFactory k          = find(wiring,     "Constant");
        ComponentFactory pin        = find(wiring,     "Pin");
        ComponentFactory bitExtend  = find(wiring,     "Bit Extender", "BitExtender", "Bit Extend");
        ComponentFactory not        = find(gates,      "NOT Gate", "NOT");
        ComponentFactory and        = find(gates,      "AND Gate", "AND");
        ComponentFactory or         = find(gates,      "OR Gate",  "OR");
        ComponentFactory mux        = find(plexers,    "Multiplexer", "Mux");
        ComponentFactory podd       = find(gates,      "Odd Parity",  "Parity (Odd)", "Parity-Odd");
        ComponentFactory pevn       = find(gates,      "Even Parity", "Parity (Even)","Parity-Even");

        return new Factories(cmp, mux, k, bitExtend, not, and, or, podd, pevn, pin);
    }

    /** Lanza excepción si falta alguno de los factories requeridos. */
    public void validate(String... required) {
        for (String r : required) {
            boolean ok = switch (r) {
                case "cmp" -> cmpF != null;
                case "mux" -> muxF != null;
                case "const" -> constF != null;
                case "bitExtend" -> bitExtendF != null;
                case "not" -> notF != null;
                case "and" -> andF != null;
                case "or" -> orF != null;
                case "oddParity" -> oddParityF != null;
                case "evenParity" -> evenParityF != null;
                case "pin" -> pinF != null;
                default -> true;
            };
            if (!ok) throw new IllegalStateException("Factory requerido no disponible: " + r);
        }
    }

    @Override public String toString() {
        return "Factories{" +
                "cmp=" + (cmpF!=null) +
                ", mux=" + (muxF!=null) +
                ", const=" + (constF!=null) +
                ", bitExtend=" + (bitExtendF!=null) +
                ", not=" + (notF!=null) +
                ", and=" + (andF!=null) +
                ", or=" + (orF!=null) +
                ", oddParity=" + (oddParityF!=null) +
                ", evenParity=" + (evenParityF!=null) +
                ", pin=" + (pinF!=null) +
                '}';
    }

    /* ==================== Helpers ==================== */

    private static Library getLib(LogisimFile lf, String primary, String... alts) {
        if (lf == null) return null;
        Library lib = lf.getLibrary(primary);
        if (lib != null) return lib;
        if (alts != null) {
            for (String a : alts) {
                lib = lf.getLibrary(a);
                if (lib != null) return lib;
            }
        }
        return null;
    }

    private static ComponentFactory find(Library lib, String primaryName, String... altNames) {
        if (lib == null) return null;
        ComponentFactory f = FactoryLookup.findFactory(lib, primaryName);
        if (f != null) return f;
        if (altNames != null) {
            for (String n : altNames) {
                f = FactoryLookup.findFactory(lib, n);
                if (f != null) return f;
            }
        }
        return null;
    }
}

