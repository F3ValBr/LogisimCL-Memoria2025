package com.cburch.logisim.verilog.comp.auxiliary.netconn;

/**
 * Enum representing the direction of a pin or connection.
 * Possible values are INPUT, OUTPUT, INOUT, and UNKNOWN.
 * Provides methods for JSON serialization and deserialization.
 */
public enum PortDirection {
    INPUT("input"),
    OUTPUT("output"),
    INOUT("inout"),
    UNKNOWN("unknown");

    private final String dirValue;

    PortDirection(String dirValue) {
        this.dirValue = dirValue;
    }

    public String getJsonValue() {
        return dirValue;
    }

    public static PortDirection fromJson(String value) {
        if (value == null || value.isEmpty()) {
            return UNKNOWN;
        }
        for (PortDirection dir : values()) {
            if (dir.dirValue.equalsIgnoreCase(value)) {
                return dir;
            }
        }
        throw new IllegalArgumentException("Unknown direction: " + value);
    }
}