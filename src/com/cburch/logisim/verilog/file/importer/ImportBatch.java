package com.cburch.logisim.verilog.file.importer;

import com.cburch.logisim.circuit.Circuit;
import com.cburch.logisim.circuit.CircuitMutation;
import com.cburch.logisim.circuit.Wire;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.verilog.std.Strings;

import java.util.ArrayList;
import java.util.List;

// Acumulador simple para un módulo
public final class ImportBatch {
    final Circuit circuit;
    private final List<Object> pending = new ArrayList<>();

    public ImportBatch(Circuit circuit) {
        this.circuit = circuit;
    }

    public void add(Component c) { pending.add(c); }
    public void add(Wire w)      { pending.add(w); }

    public void commit(Project proj, String actionKey) {
        if (pending.isEmpty()) return;
        CircuitMutation m = new CircuitMutation(circuit);
        for (Object o : pending) {
            if (o instanceof Component c) m.add(c);
            else if (o instanceof Wire w) m.add(w);
        }
        proj.doAction(m.toAction(Strings.getter(actionKey)));
        pending.clear();
    }
}
