package com.cburch.logisim.verilog.file.jsonhdlr;

import com.cburch.logisim.verilog.comp.impl.VerilogModuleImpl;
import com.cburch.logisim.verilog.comp.auxiliary.ModulePort;
import com.cburch.logisim.verilog.comp.auxiliary.netconn.PortDirection;
import com.fasterxml.jackson.databind.JsonNode;

import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

public record YosysModuleDTO(String name, JsonNode moduleNode) {

    public Set<String> cellNames() {
        JsonNode cells = moduleNode.path("cells");
        if (!cells.isObject()) return Set.of();
        LinkedHashSet<String> out = new LinkedHashSet<>();
        cells.fieldNames().forEachRemaining(out::add);
        return out;
    }

    public Optional<YosysCellDTO> getCell(String cellName) {
        JsonNode cell = moduleNode.path("cells").get(cellName);
        if (cell == null || !cell.isObject()) return Optional.empty();
        return Optional.of(parseCell(cellName, cell));
    }

    public Stream<YosysCellDTO> cells() {
        JsonNode cells = moduleNode.path("cells");
        if (!cells.isObject()) return Stream.empty();
        Iterable<Map.Entry<String,JsonNode>> it = cells::fields;
        return StreamSupport.stream(it.spliterator(), false)
                .map(e -> parseCell(e.getKey(), e.getValue()));
    }

    public Map<String,String> ports() {
        JsonNode p = moduleNode.path("ports");
        if (!p.isObject()) return Map.of();
        Map<String,String> out = new LinkedHashMap<>();
        p.fieldNames().forEachRemaining(name -> {
            JsonNode n = p.path(name).path("direction");
            out.put(name, n.asText(""));
        });
        return out;
    }

    public Map<String, YosysMemoryDTO> memories() {
        JsonNode m = moduleNode.path("memories");
        if (!m.isObject()) return Map.of();
        Map<String, YosysMemoryDTO> out = new LinkedHashMap<>();
        m.fields().forEachRemaining(e -> {
            String memId = e.getKey();
            JsonNode n = e.getValue();

            int width = n.path("width").asInt(0);
            int size  = n.path("size").asInt(0);
            int start = n.path("start_offset").asInt(0);
            Map<String,Object> attrs = new LinkedHashMap<>();
            JsonNode a = n.path("attributes");
            if (a.isObject()) a.fields().forEachRemaining(kv -> attrs.put(kv.getKey(), kv.getValue().asText()));

            out.put(memId, new YosysMemoryDTO(memId, width, size, start, attrs));
        });
        return out;
    }

    public Map<String,Object> netnames() {
        JsonNode n = moduleNode.path("netnames");
        if (!n.isObject()) return Map.of();
        Map<String,Object> out = new LinkedHashMap<>();
        n.fieldNames().forEachRemaining(k -> out.put(k, n.get(k))); // deja crudo si no necesitas parseo ahora
        return out;
    }

    /* ---------- parse helpers ---------- */

    private static YosysCellDTO parseCell(String cellName, JsonNode cellNode) {
        String typeId = optText(cellNode, "type", "<unknown>");

        Map<String,String> parameters     = readStringMap(cellNode.path("parameters"));
        Map<String,Object> attributes     = readObjectMap(cellNode.path("attributes"));
        Map<String,String> portDirections = readStringMap(cellNode.path("port_directions"));
        Map<String,List<Object>> connections = readConnections(cellNode.path("connections"));

        return new YosysCellDTO(cellName, typeId, parameters, attributes, portDirections, connections);
    }

    private static String optText(JsonNode n, String field, String def) {
        JsonNode x = n.get(field);
        return (x != null && x.isTextual()) ? x.asText() : def;
    }

    private static Map<String, String> readStringMap(JsonNode obj) {
        if (obj == null || obj.isMissingNode() || !obj.isObject()) return Map.of();
        Map<String, String> out = new LinkedHashMap<>();
        obj.fields().forEachRemaining(e -> out.put(e.getKey(), e.getValue().asText()));
        return out;
    }

    private static Map<String, Object> readObjectMap(JsonNode obj) {
        if (obj == null || obj.isMissingNode() || !obj.isObject()) return Map.of();
        Map<String, Object> out = new LinkedHashMap<>();
        obj.fields().forEachRemaining(e -> {
            JsonNode v = e.getValue();
            Object val = v.isNumber() ? v.numberValue()
                    : v.isBoolean() ? v.booleanValue()
                    : v.asText();
            out.put(e.getKey(), val);
        });
        return out;
    }

    /** connections: cada valor es lista de enteros (nets) o strings "0"/"1" */
    private static Map<String, List<Object>> readConnections(JsonNode obj) {
        if (obj == null || obj.isMissingNode() || !obj.isObject()) return Map.of();
        Map<String, List<Object>> out = new LinkedHashMap<>();
        obj.fields().forEachRemaining(e -> {
            String port = e.getKey();
            JsonNode arr = e.getValue();
            if (arr.isArray()) {
                List<Object> bits = new ArrayList<>(arr.size());
                arr.forEach(el -> bits.add(el.isIntegralNumber() ? el.asInt() : el.asText()));
                out.put(port, bits);
            } else {
                out.put(port, List.of());
            }
        });
        return out;
    }

    public static void readModulePorts(JsonNode portsNode, VerilogModuleImpl mod) {
        if (portsNode == null || !portsNode.isObject()) return;

        Iterator<Map.Entry<String, JsonNode>> it = portsNode.fields();
        while (it.hasNext()) {
            var e = it.next();
            String portName = e.getKey();
            JsonNode p = e.getValue();

            PortDirection dir = PortDirection.fromJson(p.path("direction").asText("unknown"));
            JsonNode bits = p.path("bits");
            int[] netIds = new int[bits.size()];

            for (int i = 0; i < bits.size(); i++) {
                JsonNode b = bits.get(i);
                if (b.isInt() || b.isLong()) {
                    netIds[i] = b.asInt();
                } else {
                    String s = b.asText();
                    netIds[i] = switch (s) {
                        case "0" -> ModulePort.CONST_0;
                        case "1" -> ModulePort.CONST_1;
                        case "x", "X" -> ModulePort.CONST_X;
                        default -> throw new IllegalArgumentException("Unknown top bit token: " + s);
                    };
                }
            }
            mod.addModulePort(new ModulePort(portName, dir, netIds));
        }
    }
}
