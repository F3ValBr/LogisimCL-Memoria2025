package com.cburch.logisim.verilog.comp.specs.gatelvl;

import java.util.Locale;

public class RegisterGateUtils {
    public enum Pol { POS, NEG, NA }

    public record RegGateConfig(
            RegisterGateOp base,
            boolean clkPosEdge,   // Ignorado para latches
            boolean hasEnable, Pol enablePol,
            boolean hasSet,    Pol setPol,
            boolean hasReset,  Pol resetPol,
            int initValue // -1 = none, 0/1 = valor explícito
    ) {}

    public static final class FFNameParser {

        /**
         * Parsea un typeId de Yosys (p.ej. $_DFFE_PP0_, $_DLATCHSR_PNN_)
         * en una configuración compacta de flip-flop o latch.
         * Reglas:
         * - FF (no latch): primer P/N → flanco de clock; resto P/N → E, luego S, luego R.
         * - Latch: no hay clock; primer P/N → Enable; resto P/N → S, luego R.
         * - '0'/'1' fija initValue (si aparece varias veces, la última gana).
         * - Si existe E/S/R y no viene P/N para esa señal → default POS.
         */
        public static RegGateConfig parse(String typeId) {
            if (typeId == null || typeId.isBlank()) return null;

            // Normaliza (case-insensitive) y elimina "$_" inicial y "_" finales
            final String tUpper = typeId.toUpperCase(Locale.ROOT);
            String core = tUpper.replaceAll("^\\$_", "").replaceAll("_+$", ""); // ej: "DFFE_PP0"
            int us = core.indexOf('_');
            final String baseStr = (us >= 0 ? core.substring(0, us) : core);   // ej: "DFFE"
            final String cfg     = (us >= 0 ? core.substring(us + 1) : "");    // ej: "PP0"

            // Resuelve la operación base (lanza si no coincide)
            final RegisterGateOp base = RegisterGateOp.fromYosys("$_" + baseStr + "_");
            final boolean isLatch = base.isLatch(); // asume que lo tienes; si no, añade método

            // Descubre qué señales "existen" por familia base
            final boolean hasE = baseStr.contains("E") || isLatch; // latches siempre tienen enable
            final boolean hasS = baseStr.contains("S");            // familias *S* traen set
            final boolean hasR = baseStr.contains("R") || baseStr.contains("ADFF"); // ADFF es async reset (R implícita)

            // Valores por defecto
            boolean clkPos = true;          // para FF (ignorarlo si latch)
            Pol ePol = Pol.NA, sPol = Pol.NA, rPol = Pol.NA;
            int init = -1;

            // Cursor en el string cfg
            int i = 0;

            if (!isLatch) {
                // === Flip-Flop ===
                // 1) Primer P/N (si existe) define flanco de clock
                if (i < cfg.length()) {
                    char c0 = cfg.charAt(i);
                    if (c0 == 'P' || c0 == 'N') {
                        clkPos = (c0 == 'P');
                        i++;
                    }
                }
                // 2) El resto P/N se asignan E → S → R según existan
                for (; i < cfg.length(); i++) {
                    char c = cfg.charAt(i);
                    if (c == 'P' || c == 'N') {
                        Pol pol = (c == 'P') ? Pol.POS : Pol.NEG;
                        if (hasE && ePol == Pol.NA) { ePol = pol; continue; }
                        if (hasS && sPol == Pol.NA) { sPol = pol; continue; }
                        if (hasR && rPol == Pol.NA) { rPol = pol; continue; }
                    } else if (c == '0') {
                        init = 0;
                    } else if (c == '1') {
                        init = 1;
                    } // otros símbolos se ignoran
                }
            } else {
                // === Latch (nivel) ===
                // No hay clock; el primer P/N afecta a Enable (E)
                for (; i < cfg.length(); i++) {
                    char c = cfg.charAt(i);
                    if (c == 'P' || c == 'N') {
                        Pol pol = (c == 'P') ? Pol.POS : Pol.NEG;
                        if (hasE && ePol == Pol.NA) { ePol = pol; continue; } // Enable primero
                        if (hasS && sPol == Pol.NA) { sPol = pol; continue; }
                        if (hasR && rPol == Pol.NA) { rPol = pol; continue; }
                    } else if (c == '0') {
                        init = 0;
                    } else if (c == '1') {
                        init = 1;
                    }
                }
            }

            // Defaults: si la señal existe y quedó NA, usa POS (activa alta)
            if (hasE && ePol == Pol.NA) ePol = Pol.POS;
            if (hasS && sPol == Pol.NA) sPol = Pol.POS;
            if (hasR && rPol == Pol.NA) rPol = Pol.POS;

            return new RegGateConfig(
                    base,
                    clkPos,
                    hasE, ePol,
                    hasS, sPol,
                    hasR, rPol,
                    init
            );
        }
    }
}
