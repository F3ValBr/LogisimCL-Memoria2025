package com.cburch.logisim.verilog.comp.auxiliary;

import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;

import java.util.Objects;

/**
 * Represents the signature of a port in a Verilog cell, including the cell it belongs to,
 * the port name, and its portDirection (INPUT, OUTPUT, INOUT).
 */
public final class PortSignature {
    private final VerilogCell cell;     // Celda a la que pertenece el puerto
    private final String portName;      // Nombre del puerto en la celda
    private final PortDirection portDirection;  // INPUT, OUTPUT, INOUT

    /**
     * Constructor for PortSignature
     *
     * @param cell      The VerilogCell to which the port belongs
     * @param portName  The name of the port within the cell
     * @param portDirection The portDirection of the port (INPUT, OUTPUT, INOUT)
     */
    public PortSignature(VerilogCell cell, String portName, PortDirection portDirection) {
        this.cell = cell;
        this.portName = portName;
        this.portDirection = portDirection;
    }

    public VerilogCell cell() {
        return cell;
    }
    public String portName() {
        return portName;
    }
    public PortDirection direction() {
        return portDirection;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortSignature that)) return false;

        return portDirection == that.portDirection
                && Objects.equals(cell, that.cell)
                && Objects.equals(portName, that.portName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(cell, portName, portDirection);
    }
}
