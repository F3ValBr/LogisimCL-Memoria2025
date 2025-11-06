package com.cburch.logisim.verilog.comp.impl;

import com.cburch.logisim.verilog.comp.auxiliary.CellType;
import com.cburch.logisim.verilog.comp.auxiliary.PortEndpoint;
import com.cburch.logisim.verilog.comp.specs.CellAttribs;
import com.cburch.logisim.verilog.comp.specs.CellParams;

import java.util.*;

public abstract class AbstractVerilogCell implements VerilogCell {
    protected String name;
    protected CellType cellType;
    protected CellParams params;
    protected CellAttribs attribs;
    protected List<PortEndpoint> endpoints = new ArrayList<>();

    protected AbstractVerilogCell(String name, CellType type, CellParams params, CellAttribs attribs) {
        this.name = name;
        this.cellType = type;
        this.params = params;
        this.attribs = attribs;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public CellType type() {
        return cellType;
    }

    @Override
    public CellParams params() {
        return params;
    }

    @Override
    public CellAttribs attribs() {
        return attribs;
    }

    @Override
    public List<PortEndpoint> endpoints() {
        return endpoints;
    }

    @Override
    public int portWidth(String portName) {
        if (portName == null || portName.isEmpty()) {
            throw new IllegalArgumentException("Port name cannot be null or empty");
        }
        return (int) endpoints.stream()
                .filter(endpoint -> endpoint.getPortName().equals(portName))
                .count();
    }

    @Override
    public List<String> getPortNames() {
        Set<String> portNames = new LinkedHashSet<>();
        for (PortEndpoint endpoint : endpoints) {
            portNames.add(endpoint.getPortName());
        }
        return new ArrayList<>(portNames);
    }

    @Override
    public void addPortEndpoint(PortEndpoint endpoint) {
        if (endpoint == null) {
            throw new IllegalArgumentException("PortEndpoint cannot be null");
        }
        endpoints.add(endpoint);
    }

    @Override
    public String typeId() {
        return cellType != null ? cellType.typeId() : null;
    }

    @Override
    public String kind() {
        return cellType != null ? cellType.kind().toString() : null;
    }

    @Override
    public String level() {
        return cellType != null ? cellType.level().toString() : null;
    }

    /**
     * Devuelve todos los endpoints asociados a un puerto (multi-bit o escalar).
     * Si no existen, devuelve una lista vacía (nunca null).
     */
    public List<PortEndpoint> getPortEndpoints(String portName) {
        if (portName == null) return List.of();
        List<PortEndpoint> list = new ArrayList<>();
        for (PortEndpoint ep : endpoints) {
            if (portName.equals(ep.getPortName())) {
                list.add(ep);
            }
        }
        // Ordenar por índice de bit ascendente (LSB → MSB)
        list.sort(Comparator.comparingInt(PortEndpoint::getBitIndex));
        return list;
    }

    /**
     * Devuelve el primer endpoint del puerto (útil para puertos escalares).
     * Si no existe, devuelve null.
     */
    public PortEndpoint getFirstPortEndpoint(String portName) {
        for (PortEndpoint ep : endpoints) {
            if (portName.equals(ep.getPortName())) {
                return ep;
            }
        }
        return null;
    }

    /**
     * Devuelve el endpoint específico de un bit dentro de un puerto (si existe).
     * @param portName nombre del puerto
     * @param bitIndex índice del bit (0 = LSB)
     * @return el PortEndpoint correspondiente, o null si no se encuentra
     */
    public PortEndpoint getPortEndpoint(String portName, int bitIndex) {
        for (PortEndpoint ep : endpoints) {
            if (portName.equals(ep.getPortName()) && ep.getBitIndex() == bitIndex) {
                return ep;
            }
        }
        return null;
    }
}
