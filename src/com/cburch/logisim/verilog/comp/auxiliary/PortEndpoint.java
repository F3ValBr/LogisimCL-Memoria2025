package com.cburch.logisim.verilog.comp.auxiliary;

import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.cburch.logisim.verilog.comp.impl.VerilogCell;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.BitRef;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.NetBit;

import java.util.Objects;

/**
 * Represents an endpoint of a port connection in a Verilog cell.
 * Contains information about the port signature, bit index, and bit reference.
 */
public final class PortEndpoint {
    private final PortSignature sig;    // Firma del puerto
    private final int bitIndex;         // Posición dentro del bus
    private final BitRef bitRef;        // Referencia al bit específico

    /**
     * Endpoint constructor
     *
     * @param sig PortSignature of the port
     * @param bitIndex Index of the bit within the port
     * @param bitRef Reference to the specific bit
     */
    public PortEndpoint(PortSignature sig, int bitIndex, BitRef bitRef) {
        this.sig = sig;
        this.bitIndex = bitIndex;
        this.bitRef = bitRef;
    }

    public VerilogCell getCell() {
        return sig.cell();
    }

    public String getPortName() {
        return sig.portName();
    }

    public int getBitIndex() {
        return bitIndex;
    }

    public PortDirection getDirection() {
        return sig.direction();
    }

    public BitRef getBitRef() {
        return bitRef;
    }

    public Integer getNetIdOrNull() {
        return (bitRef instanceof NetBit nb) ? nb.getNetId() : null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PortEndpoint that)) return false;

        return bitIndex == that.bitIndex
                && Objects.equals(sig, that.sig)
                && Objects.equals(bitRef, that.bitRef);
    }

    @Override
    public int hashCode() {
        return Objects.hash(sig, bitIndex, bitRef);
    }
}
