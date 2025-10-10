package com.cburch.logisim.std.wiring;


import com.cburch.logisim.data.Attribute;


import java.util.Arrays;
import java.util.List;

/**
 * Extiende TunnelAttributes para exponer:
 *  - BitLabeledTunnel.BIT_SPECS  (String)
 *  - BitLabeledTunnel.ATTR_OUTPUT (Boolean)
 * Compatible con Logisim 2.7.1 (getAttributes() -> Attribute[])
 */
class BitLabeledTunnelAttributes extends TunnelAttributes {
    // ===== Atributos extra =====
    private String  bitSpecs = "";
    private Boolean output   = Boolean.FALSE;

    // ===== Exponer los atributos extra en el panel de propiedades =====
    @Override
    public List<Attribute<?>> getAttributes() {
        List<Attribute<?>> base = super.getAttributes();
        Attribute<?>[] arr = new Attribute<?>[base.size() + 2];
        for (int i = 0; i < base.size(); i++) arr[i] = base.get(i);
        arr[base.size()]     = BitLabeledTunnel.BIT_SPECS;
        arr[base.size() + 1] = BitLabeledTunnel.ATTR_OUTPUT;
        return Arrays.asList(arr);
    }

    @Override
    public boolean containsAttribute(Attribute<?> attr) {
        return super.containsAttribute(attr)
                || attr == BitLabeledTunnel.BIT_SPECS
                || attr == BitLabeledTunnel.ATTR_OUTPUT;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <V> V getValue(Attribute<V> attr) {
        if (attr == BitLabeledTunnel.BIT_SPECS)   return (V) bitSpecs;
        if (attr == BitLabeledTunnel.ATTR_OUTPUT) return (V) output;
        return super.getValue(attr);
    }

    @Override
    public <V> void setValue(Attribute<V> attr, V value) {
        if (attr == BitLabeledTunnel.BIT_SPECS) {
            bitSpecs = (String) value;
        } else if (attr == BitLabeledTunnel.ATTR_OUTPUT) {
            output = (Boolean) value;
        } else {
            // delega atributos base (FACING, WIDTH, LABEL, LABEL_FONT)
            super.setValue(attr, value);
        }
        // invalida bounds como hace TunnelAttributes y notifica cambio
        fireAttributeValueChanged(attr, value);
    }

    // ====== Getters/Setters de conveniencia ======
    public String  getBitSpecs() { return bitSpecs; }
    public Boolean isOutput()    { return output; }
    public void setBitSpecs(String s) { bitSpecs = s; }
    public void setOutput(Boolean o)  { output   = o; }
}