package com.cburch.logisim.verilog.comp.specs.ips;

import java.util.Optional;

public enum KnownIP {
    RAM("ram"),
    ROM("rom");

    private final String typeId;

    KnownIP(String typeId) {
        this.typeId = typeId;
    }

    public String typeId() {
        return typeId;
    }

    /** Busca si coincide el typeId de la celda. */
    public static Optional<KnownIP> from(String id) {
        if (id == null) return Optional.empty();
        String norm = id.trim().toLowerCase();
        for (KnownIP ip : values()) {
            if (ip.typeId.equalsIgnoreCase(norm)) return Optional.of(ip);
        }
        return Optional.empty();
    }

    /** Retorna true si el typeId está en la lista. */
    public static boolean isKnown(String id) {
        return from(id).isPresent();
    }
}
