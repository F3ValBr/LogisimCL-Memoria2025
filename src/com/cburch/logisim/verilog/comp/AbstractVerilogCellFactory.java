package com.cburch.logisim.verilog.comp;

import com.cburch.logisim.verilog.comp.auxiliary.PortEndpoint;
import com.cburch.logisim.verilog.comp.auxiliary.PortSignature;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.*;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public abstract class AbstractVerilogCellFactory implements VerilogCellFactory {

    protected int parseBin(String s) {
        if (s == null || s.isEmpty()) return 0;
        return Integer.parseUnsignedInt(s, 2);
    }

    protected PortDirection toDirection(String d) {
        if (d == null) return PortDirection.UNKNOWN;
        return switch (d.toLowerCase()) {
            case "input" -> PortDirection.INPUT;
            case "output" -> PortDirection.OUTPUT;
            case "inout" -> PortDirection.INOUT;
            default -> PortDirection.UNKNOWN;
        };
    }

    /** Convert element from JSON (Integer or "0"/"1") to BitRef
     *
     * @param raw The raw value to convert, can be Integer or String.
     */
    protected BitRef toBitRef(Object raw) {
        if (raw instanceof Integer i) return new NetBit(i);
        if (raw instanceof String s) {
            return switch (s) {
                case "0" -> Const0.getInstance();
                case "1" -> Const1.getInstance();
                case "x" -> ConstX.getInstance();
                case "z" -> ConstZ.getInstance();
                default -> throw new IllegalArgumentException("Bit desconocido: " + raw);
            };
        }
        throw new IllegalArgumentException("Bit no soportado: " + raw);
    }

    /** Creates PortEndpoint objects for each port in the cell.
     *
     * @param cell The VerilogCell to which the endpoints belong.
     * @param portDirections Map of port names to their directions (INPUT/OUTPUT/INOUT).
     * @param connections Map of port names to lists of bits (NetBit, Const0, Const1).
     */
    protected void buildEndpoints(
            VerilogCell cell,
            Map<String, String> portDirections,
            Map<String, List<Object>> connections
    ) {
        // Unir nombres de puertos presentes en directions y/o en connections
        Set<String> portNames = new LinkedHashSet<>();
        portNames.addAll(portDirections.keySet());
        portNames.addAll(connections.keySet());

        for (String portName : portNames) {
            PortDirection dir = toDirection(portDirections.get(portName)); // UNKNOWN si falta o inválido
            List<Object> rawBits = connections.getOrDefault(portName, List.of());

            PortSignature signature = new PortSignature(
                    cell,       // celda dueña del puerto
                    portName,   // nombre del puerto
                    dir         // dirección del puerto (INPUT/OUTPUT/INOUT/UNKNOWN)
            );

            // Endpoints bit a bit (índice == posición en la lista; LSB primero)
            for (int i = 0; i < rawBits.size(); i++) {
                BitRef bit = toBitRef(rawBits.get(i));

                PortEndpoint ep = new PortEndpoint(
                        signature,   // PortSignature (celda, nombre de puerto, dirección)
                        i,           // índice del bit en el bus (LSB = 0)
                        bit          // BitRef (NetBit | Const0 | Const1)
                );
                cell.addPortEndpoint(ep);
            }
            // Si no hay bits en connections para este puerto, no se crean endpoints (width = 0).
        }
    }

}
