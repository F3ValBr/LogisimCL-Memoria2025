/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.memory;

import java.awt.Color;
import java.awt.Graphics;
import java.util.ArrayList;
import java.util.List;

import com.cburch.logisim.data.*;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceFactory;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.tools.key.BitWidthConfigurator;
import com.cburch.logisim.util.GraphicsUtil;
import com.cburch.logisim.util.StringUtil;

public class Register extends InstanceFactory {
	private static final int DELAY = 8;

    // Tipo de reset (asincrónico, sincrónico, ninguno)
    static final AttributeOption ASYNC_RESET
        = new AttributeOption("asyncReset", Strings.getter("registerAsyncResetOpt"));
    static final AttributeOption SYNC_RESET
        = new AttributeOption("syncReset",  Strings.getter("registerSyncResetOpt"));
    static final AttributeOption NO_RESET
        = new AttributeOption("noReset",    Strings.getter("registerNoResetOpt"));
    static final Attribute<AttributeOption> RESET_TYPE
        = Attributes.forOption("resetType", Strings.getter("registerResetTypeAttr"),
            new AttributeOption[] { ASYNC_RESET, SYNC_RESET, NO_RESET });

    // Polaridad del reset (activa alta o activa baja)
    static final AttributeOption RST_ACTIVE_HIGH
        = new AttributeOption("rstActiveHigh", Strings.getter("registerRstActiveHighOpt"));
    static final AttributeOption RST_ACTIVE_LOW
        = new AttributeOption("rstActiveLow",  Strings.getter("registerRstActiveLowOpt"));
    static final Attribute<AttributeOption> RESET_POLARITY
        = Attributes.forOption("resetPolarity", Strings.getter("registerResetPolarityAttr"),
            new AttributeOption[] { RST_ACTIVE_HIGH, RST_ACTIVE_LOW });

    // Valor de reset configurable (texto: dec, 0x.., 0b..)
    static final Attribute<String> RESET_VALUE =
            Attributes.forString("resetValue", Strings.getter("registerResetValueAttr"));

    // Atributos nuevos (mostrar u ocultar puertos)
    static final Attribute<Boolean> HAS_EN  = Attributes.forBoolean("regHasEnable",  Strings.getter("registerHasEnableAttr"));

    // Polaridad del enable (activa alta o activa baja)
    static final AttributeOption EN_ACTIVE_HIGH
            = new AttributeOption("enActiveHigh", Strings.getter("registerEnActiveHighOpt"));
    static final AttributeOption EN_ACTIVE_LOW
            = new AttributeOption("enActiveLow",  Strings.getter("registerEnActiveLowOpt"));
    static final Attribute<AttributeOption> EN_POLARITY
            = Attributes.forOption("enablePolarity", Strings.getter("registerEnablePolarityAttr"),
            new AttributeOption[] { EN_ACTIVE_HIGH, EN_ACTIVE_LOW });

    public Register() {
		super("Register", Strings.getter("registerComponent"));
		setAttributes(
            new Attribute[] {
				StdAttr.WIDTH, StdAttr.TRIGGER,
				StdAttr.LABEL, StdAttr.LABEL_FONT,
                RESET_TYPE, RESET_POLARITY,
                RESET_VALUE,
                HAS_EN, EN_POLARITY
			},
            new Object[] {
				BitWidth.create(8), StdAttr.TRIG_RISING,
				"", StdAttr.DEFAULT_LABEL_FONT,
                ASYNC_RESET, RST_ACTIVE_HIGH,
                "0",
                Boolean.TRUE, EN_ACTIVE_HIGH
			});
		setKeyConfigurator(new BitWidthConfigurator(StdAttr.WIDTH));
		setOffsetBounds(Bounds.create(-30, -20, 30, 40));
		setIconName("register.gif");
		setInstancePoker(RegisterPoker.class);
		setInstanceLogger(RegisterLogger.class);
	}

    @Override
    protected void configureNewInstance(Instance instance) {
        Bounds bds = instance.getBounds();
        instance.setTextField(StdAttr.LABEL, StdAttr.LABEL_FONT,
                bds.getX() + bds.getWidth() / 2, bds.getY() - 3,
                GraphicsUtil.H_CENTER, GraphicsUtil.V_BASELINE);

        // Construir puertos según atributos
        recomputePorts(instance);
        instance.addAttributeListener();
    }

    @Override
    public void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        if (attr == StdAttr.WIDTH
        || attr == StdAttr.TRIGGER
        || attr == HAS_EN
        || attr == RESET_TYPE
        || attr == RESET_POLARITY) {
            recomputePorts(instance);
        }
    }

    /* ===== helpers de modo y polaridad ===== */
    private static boolean hasEn(Instance inst) {
        Boolean b = inst.getAttributeValue(HAS_EN);
        return b != null && b.booleanValue();
    }

    private static boolean enActiveHigh(Instance inst) {
        AttributeOption opt = inst.getAttributeValue(EN_POLARITY);
        return opt == null || opt == EN_ACTIVE_HIGH;
    }

    private static boolean hasRst(Instance inst) {
        AttributeOption t = inst.getAttributeValue(RESET_TYPE);
        return t != NO_RESET;
    }
    private static boolean rstIsSync(Instance inst) {
        return inst.getAttributeValue(RESET_TYPE) == SYNC_RESET;
    }
    private static boolean rstActiveHigh(Instance inst) {
        return inst.getAttributeValue(RESET_POLARITY) == RST_ACTIVE_HIGH;
    }

    private static int parseResetValue(AttributeSet attrs, BitWidth w) {
        String s = attrs.getValue(RESET_VALUE);
        if (s == null || s.isBlank()) s = "0";
        s = s.trim().toLowerCase();

        long val;
        try {
            if (s.startsWith("0x")) {
                val = Long.parseUnsignedLong(s.substring(2), 16);
            } else if (s.startsWith("0b")) {
                val = Long.parseUnsignedLong(s.substring(2), 2);
            } else {
                // decimal (permite negativo; se ajusta por máscara)
                val = Long.parseLong(s);
            }
        } catch (NumberFormatException ex) {
            // inválido → usa 0
            val = 0L;
        }

        int width = (w != null ? w.getWidth() : 8);
        if (width <= 0) return 0;

        if (width >= 32) {
            // RegisterData guarda int; si tu diseño usa >32 bits, aquí se trunca a 32.
            // Puedes cambiar RegisterData.value a long si necesitas más.
            long mask = 0xFFFF_FFFFL; // máx 32 bits
            return (int) (val & mask);
        } else {
            int mask = (1 << width) - 1;
            return (int) (val & mask);
        }
    }


    /* ===== recomputePorts: crea puertos según atributos y guarda índices en RegisterData ===== */
    private void recomputePorts(Instance instance) {
        BitWidth w = instance.getAttributeValue(StdAttr.WIDTH);

        List<Port> list = new ArrayList<>();

        Port pOut = new Port( 0,  0, Port.OUTPUT, w);
        pOut.setToolTip(Strings.getter("registerQTip"));
        list.add(pOut); // 0

        Port pIn  = new Port(-30, 0, Port.INPUT,  w);
        pIn.setToolTip(Strings.getter("registerDTip"));
        list.add(pIn); // 1

        Port pCk  = new Port(-20, 20, Port.INPUT, 1);
        pCk.setToolTip(Strings.getter("registerClkTip"));
        list.add(pCk); // 2

        if (hasRst(instance)) {
            Port pRst = new Port(-10, 20, Port.INPUT, 1);
            String rv = instance.getAttributeValue(RESET_VALUE);
            if (rv == null) rv = "0";
            pRst.setToolTip(Strings.getter("registerRstTip", rv));
            list.add(pRst); // 3 si existe
        }
        if (hasEn(instance)) {
            Port pEn = new Port(-30, 10, Port.INPUT, 1);
            pEn.setToolTip(Strings.getter("registerEnableTip"));
            list.add(pEn); // 3 o 4 según haya CLR
        }

        instance.setPorts(list.toArray(new Port[0]));
        instance.recomputeBounds();

        Bounds bds = instance.getBounds();
        instance.setTextField(StdAttr.LABEL, StdAttr.LABEL_FONT,
                bds.getX() + bds.getWidth() / 2, bds.getY() - 3,
                GraphicsUtil.H_CENTER, GraphicsUtil.V_BASELINE);
    }

    private static void computeIdxs(Instance inst, RegisterData d) {
        // Orden construido en recomputePorts:
        d.OUT = 0;
        d.IN  = 1;
        d.CK  = 2;

        boolean rst = hasRst(inst);
        boolean en  = hasEn(inst);

        if (rst) {
            d.CLR = 3;
            d.EN  = en ? 4 : -1;
        } else {
            d.CLR = -1;
            d.EN  = en ? 3 : -1;
        }
    }

    /* ===== propagate: reset async/sync + polaridad + enable ===== */
    @Override
    public void propagate(InstanceState state) {
        RegisterData data = (RegisterData) state.getData();
        if (data == null) { data = new RegisterData(); state.setData(data); }

        // Calcular índices en base al Instance y atributos
        computeIdxs(state.getInstance(), data);

        BitWidth dataWidth = state.getAttributeValue(StdAttr.WIDTH);
        Object triggerType = state.getAttributeValue(StdAttr.TRIGGER);

        Value vCK  = state.getPort(data.CK);
        Value vIN  = state.getPort(data.IN);
        Value vCLR = (data.CLR >= 0) ? state.getPort(data.CLR) : Value.FALSE;

        // EN
        boolean enVisible   = (data.EN >= 0) && hasEn(state.getInstance());
        boolean enHigh      = enActiveHigh(state.getInstance());

        boolean enConnected = enVisible && state.isPortConnected(data.EN);
        Value   vEN         = enVisible ? state.getPort(data.EN) : Value.TRUE;

        boolean enAsserted  = !enVisible || !enConnected || isAsserted(vEN, enHigh);

        boolean triggered   = data.updateClock(vCK, triggerType);

        // RST
        boolean rstVisible  = (data.CLR >= 0);
        boolean rstSync     = rstVisible && rstIsSync(state.getInstance());
        boolean rstHigh     = rstActiveHigh(state.getInstance());
        boolean rstAsserted = rstVisible && isAsserted(vCLR, rstHigh);

        int rstValue = parseResetValue(state.getAttributeSet(), dataWidth);

        if (!rstSync) {
            if (rstAsserted) {
                data.value = rstValue;
            } else if (triggered && enAsserted) {
                if (vIN.isFullyDefined()) data.value = vIN.toIntValue();
            }
        } else {
            if (triggered) {
                if (rstAsserted) {
                    data.value = rstValue;
                } else if (enAsserted) {
                    if (vIN.isFullyDefined()) data.value = vIN.toIntValue();
                }
            }
        }

        state.setPort(data.OUT, Value.createKnown(dataWidth, data.value), DELAY);
    }

    /* ===== helper de detección de “señal activa” con polaridad ===== */
    private static boolean isAsserted(Value v, boolean activeHigh) {
        if (v == Value.ERROR || v == Value.UNKNOWN) return false;
        if (v.getWidth() <= 1) {
            boolean one = (v == Value.TRUE) || (v.isFullyDefined() && v.toIntValue() == 1);
            return activeHigh == one;
        }
        if (!v.isFullyDefined()) return false;
        boolean nonZero = v.toIntValue() != 0;
        return activeHigh == nonZero;
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        Graphics g = painter.getGraphics();
        Bounds bds = painter.getBounds();

        RegisterData st = (RegisterData) painter.getData();
        if (st == null) st = new RegisterData();

        // Calcular índices según atributos actuales
        computeIdxs(painter.getInstance(), st);

        BitWidth widthVal = painter.getAttributeValue(StdAttr.WIDTH);
        int width = widthVal == null ? 8 : widthVal.getWidth();

        painter.drawBounds();
        painter.drawLabel();

        painter.drawPort(st.OUT, "Q", Direction.WEST);
        painter.drawPort(st.IN,  "D", Direction.EAST);
        painter.drawClock(st.CK, Direction.NORTH);

        if (st.CLR >= 0 && hasRst(painter.getInstance())) {
            g.setColor(Color.GRAY);
            painter.drawPort(st.CLR, Strings.get("registerRstLabel"), Direction.SOUTH);
            g.setColor(Color.BLACK);
        }
        if (st.EN >= 0 && hasEn(painter.getInstance())) {
            g.setColor(Color.GRAY);
            painter.drawPort(st.EN, Strings.get("memEnableLabel"), Direction.EAST);
            g.setColor(Color.BLACK);
        }

        String top, bottom = null;
        if (painter.getShowState()) {
            int val = st.getValue();
            String hex = StringUtil.toHexString(width, val);
            if (hex.length() <= 4) {
                top = hex;
            } else {
                int split = hex.length() - 4;
                top = hex.substring(0, split);
                bottom = hex.substring(split);
            }
        } else {
            top    = Strings.get("registerLabel");
            assert widthVal != null;
            bottom = Strings.get("registerWidthLabel", "" + widthVal.getWidth());
        }

        if (bottom == null) {
            GraphicsUtil.drawText(g, top, bds.getX() + 15, bds.getY() + 4,
                    GraphicsUtil.H_CENTER, GraphicsUtil.V_TOP);
        } else {
            GraphicsUtil.drawText(g, top,    bds.getX() + 15, bds.getY() + 3,
                    GraphicsUtil.H_CENTER, GraphicsUtil.V_TOP);
            GraphicsUtil.drawText(g, bottom, bds.getX() + 15, bds.getY() + 15,
                    GraphicsUtil.H_CENTER, GraphicsUtil.V_TOP);
        }
    }
}
