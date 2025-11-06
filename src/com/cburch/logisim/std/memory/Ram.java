/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.std.memory;

import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;

import com.cburch.logisim.circuit.CircuitState;
import com.cburch.logisim.data.Attribute;
import com.cburch.logisim.data.AttributeEvent;
import com.cburch.logisim.data.AttributeListener;
import com.cburch.logisim.data.AttributeOption;
import com.cburch.logisim.data.AttributeSet;
import com.cburch.logisim.data.AttributeSets;
import com.cburch.logisim.data.Attributes;
import com.cburch.logisim.data.BitWidth;
import com.cburch.logisim.data.Direction;
import com.cburch.logisim.data.Location;
import com.cburch.logisim.data.Value;
import com.cburch.logisim.gui.hex.HexFrame;
import com.cburch.logisim.instance.Instance;
import com.cburch.logisim.instance.InstanceData;
import com.cburch.logisim.instance.InstanceLogger;
import com.cburch.logisim.instance.InstancePainter;
import com.cburch.logisim.instance.InstanceState;
import com.cburch.logisim.instance.Port;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.proj.Project;

public class Ram extends Mem {
	static final AttributeOption BUS_COMBINED
		= new AttributeOption("combined", Strings.getter("ramBusSynchCombined"));
	static final AttributeOption BUS_ASYNCH
		= new AttributeOption("asynch", Strings.getter("ramBusAsynchCombined"));
	static final AttributeOption BUS_SEPARATE
		= new AttributeOption("separate", Strings.getter("ramBusSeparate"));

	static final Attribute<AttributeOption> ATTR_BUS =
            Attributes.forOption("bus", Strings.getter("ramBusAttr"), new AttributeOption[] {
                    BUS_COMBINED, BUS_ASYNCH, BUS_SEPARATE
            });
    static final Attribute<Boolean> CLEAR_PIN =
            Attributes.forBoolean("clearpin", Strings.getter("ramClearPin"));

	private final static Attribute<?>[] ATTRIBUTES = {
		Mem.ADDR_ATTR, Mem.DATA_ATTR, ATTR_BUS, StdAttr.TRIGGER, CLEAR_PIN
	};
	private final static Object[] DEFAULTS = {
		BitWidth.create(8), BitWidth.create(8), BUS_COMBINED, StdAttr.TRIG_RISING, Boolean.TRUE
	};

    private static final class Idx {
        int count;  // tamaño total del arreglo ps
        int OE  = -1;
        int CLR = -1;
        int CLK = -1;
        int WE  = -1;
        int DIN = -1;
    }

    private static Idx computeIdx(AttributeSet attrs) {
        Idx i = new Idx();
        int idx = MEM_INPUTS; // los primeros MEM_INPUTS los pone configureStandardPorts

        Object bus = attrs.getValue(ATTR_BUS);
        boolean asynch   = BUS_ASYNCH.equals(bus);
        boolean separate = BUS_SEPARATE.equals(bus);
        boolean clear    = Boolean.TRUE.equals(attrs.getValue(CLEAR_PIN));

        // Orden canónico:
        // OE (siempre) -> CLR (opcional) -> CLK (si no es asíncrona) -> WE/DIN (si separado)
        i.OE = idx++;                 // siempre
        if (clear) i.CLR = idx++;     // opcional

        if (!asynch) i.CLK = idx++;   // sin reloj si es asíncrona

        if (separate) {               // entradas separadas
            i.WE  = idx++;
            i.DIN = idx++;
        }

        i.count = idx;
        return i;
    }


    private final static Object[][] logOptions = new Object[9][];

	public Ram() {
		super("RAM", Strings.getter("ramComponent"), 3);
		setIconName("ram.gif");
		setInstanceLogger(Logger.class);
	}
	
	@Override
	protected void configureNewInstance(Instance instance) {
		super.configureNewInstance(instance);
		instance.addAttributeListener();
	}

    @Override
    protected void instanceAttributeChanged(Instance instance, Attribute<?> attr) {
        super.instanceAttributeChanged(instance, attr);

        if (attr == ATTR_BUS) {
            Object bus = instance.getAttributeValue(ATTR_BUS);
            if (BUS_ASYNCH.equals(bus)) {
                instance.getAttributeSet().setValue(StdAttr.TRIGGER, StdAttr.TRIG_RISING);
            }
        }

        instance.recomputeBounds();
        configurePorts(instance);
    }


    @Override
    void configurePorts(Instance instance) {
        AttributeSet attrs = instance.getAttributeSet();
        Idx i = computeIdx(attrs);

        Port[] ps = new Port[i.count];
        configureStandardPorts(instance, ps); // coloca ADDR/DATA/CS/etc. en [0..MEM_INPUTS-1]

        // Siempre OE
        ps[i.OE] = new Port(-50, 40, Port.INPUT, 1);
        ps[i.OE].setToolTip(Strings.getter("ramOETip"));

        // CLR si existe
        if (i.CLR >= 0) {
            ps[i.CLR] = new Port(-30, 40, Port.INPUT, 1);
            ps[i.CLR].setToolTip(Strings.getter("ramClrTip"));
        }

        // CLK si existe
        if (i.CLK >= 0) {
            ps[i.CLK] = new Port(-70, 40, Port.INPUT, 1);
            ps[i.CLK].setToolTip(Strings.getter("ramClkTip"));
        }

        // WE/DIN si separado
        if (i.WE  >= 0) { ps[i.WE]  = new Port(-110, 40, Port.INPUT, 1); ps[i.WE].setToolTip(Strings.getter("ramWETip")); }
        if (i.DIN >= 0) { ps[i.DIN] = new Port(-140, 20, Port.INPUT, DATA_ATTR); ps[i.DIN].setToolTip(Strings.getter("ramInTip")); }
        else {
            // bus combinado: etiqueta en DATA
            ps[DATA].setToolTip(Strings.getter("ramBusTip"));
        }

        instance.setPorts(ps);
    }

    @Override
	public AttributeSet createAttributeSet() {
		return AttributeSets.fixedSet(ATTRIBUTES, DEFAULTS);
	}

	@Override
	MemState getState(InstanceState state) {
		BitWidth addrBits = state.getAttributeValue(ADDR_ATTR);
		BitWidth dataBits = state.getAttributeValue(DATA_ATTR);

		RamState myState = (RamState) state.getData();
		if (myState == null) {
			MemContents contents = MemContents.create(addrBits.getWidth(), dataBits.getWidth());
			Instance instance = state.getInstance();
			myState = new RamState(instance, contents, new MemListener(instance));
			state.setData(myState);
		} else {
			myState.setRam(state.getInstance());
		}
		return myState;
	}

	@Override
	MemState getState(Instance instance, CircuitState state) {
		BitWidth addrBits = instance.getAttributeValue(ADDR_ATTR);
		BitWidth dataBits = instance.getAttributeValue(DATA_ATTR);

		RamState myState = (RamState) instance.getData(state);
		if (myState == null) {
			MemContents contents = MemContents.create(addrBits.getWidth(), dataBits.getWidth());
			myState = new RamState(instance, contents, new MemListener(instance));
			instance.setData(state, myState);
		} else {
			myState.setRam(instance);
		}
		return myState;
	}

	@Override
	HexFrame getHexFrame(Project proj, Instance instance, CircuitState circState) {
		RamState state = (RamState) getState(instance, circState);
		return state.getHexFrame(proj);
	}

    @Override
    public void propagate(InstanceState state) {
        AttributeSet attrs = state.getAttributeSet();
        Idx i = computeIdx(attrs);

        RamState myState = (RamState) getState(state);
        BitWidth dataBits = state.getAttributeValue(DATA_ATTR);

        // Lecturas de atributos de modo
        Object busVal = attrs.getValue(ATTR_BUS);
        boolean asynch   = BUS_ASYNCH.equals(busVal);
        boolean separate = BUS_SEPARATE.equals(busVal);
        boolean clear    = Boolean.TRUE.equals(attrs.getValue(CLEAR_PIN));

        Object trigger = attrs.getValue(StdAttr.TRIGGER);

        Value addrValue   = state.getPort(ADDR);
        boolean chipSelect   = state.getPort(CS) != Value.FALSE;
        boolean outputEnabled= state.getPort(i.OE) != Value.FALSE;

        boolean triggered = asynch || (i.CLK >= 0 && myState.setClock(state.getPort(i.CLK), trigger));

        boolean shouldClear = false;
        if (clear && i.CLR >= 0) {
            shouldClear = state.getPort(i.CLR) == Value.TRUE;
            if (shouldClear) {
                myState.getContents().clear();
            }
        }

        if (!chipSelect) {
            myState.setCurrent(-1);
            state.setPort(DATA, Value.createUnknown(dataBits), DELAY);
            return;
        }

        int addr = addrValue.toIntValue();
        if (!addrValue.isFullyDefined() || addr < 0) return;

        if (addr != myState.getCurrent()) {
            myState.setCurrent(addr);
            myState.scrollToShow(addr);
        }

        if (!shouldClear && triggered) {
            boolean shouldStore;
            if (separate) {
                shouldStore = (i.WE >= 0) && state.getPort(i.WE) != Value.FALSE;
            } else {
                shouldStore = !outputEnabled;
            }
            if (shouldStore) {
                Value dataValue = state.getPort(separate && i.DIN >= 0 ? i.DIN : DATA);
                myState.getContents().set(addr, dataValue.toIntValue());
            }
        }

        if (outputEnabled) {
            int val = myState.getContents().get(addr);
            state.setPort(DATA, Value.createKnown(dataBits, val), DELAY);
        } else {
            state.setPort(DATA, Value.createUnknown(dataBits), DELAY);
        }
    }

    @Override
    public void paintInstance(InstancePainter painter) {
        super.paintInstance(painter);

        AttributeSet attrs = painter.getAttributeSet();
        Idx i = computeIdx(attrs);

        Object busVal = attrs.getValue(ATTR_BUS);
        boolean asynch   = BUS_ASYNCH.equals(busVal);
        boolean separate = BUS_SEPARATE.equals(busVal);
        boolean clear    = Boolean.TRUE.equals(attrs.getValue(CLEAR_PIN));

        if (i.CLR >= 0 && clear) {
            painter.drawPort(i.CLR, Strings.get("ramClrLabel"), Direction.SOUTH);
        }
        if (i.CLK >= 0 && !asynch) {
            painter.drawClock(i.CLK, Direction.NORTH);
        }
        painter.drawPort(i.OE, Strings.get("ramOELabel"), Direction.SOUTH);

        if (separate) {
            if (i.WE >= 0)  painter.drawPort(i.WE,  Strings.get("ramWELabel"), Direction.SOUTH);
            if (i.DIN >= 0) painter.drawPort(i.DIN, Strings.get("ramDataLabel"), Direction.EAST);
        }
    }

    private static class RamState extends MemState
			implements InstanceData, AttributeListener {
		private Instance parent;
		private MemListener listener;
		private HexFrame hexFrame = null;
		private ClockState clockState;

		RamState(Instance parent, MemContents contents, MemListener listener) {
			super(contents);
			this.parent = parent;
			this.listener = listener;
			this.clockState = new ClockState();
			if (parent != null) parent.getAttributeSet().addAttributeListener(this);
			contents.addHexModelListener(listener);
		}
		
		void setRam(Instance value) {
			if (parent == value) return;
			if (parent != null) parent.getAttributeSet().removeAttributeListener(this);
			parent = value;
			if (value != null) value.getAttributeSet().addAttributeListener(this);
		}
		
		@Override
		public RamState clone() {
			RamState ret = (RamState) super.clone();
			ret.parent = null;
			ret.clockState = this.clockState.clone();
			ret.getContents().addHexModelListener(listener);
			return ret;
		}
		
		// Retrieves a HexFrame for editing within a separate window
		public HexFrame getHexFrame(Project proj) {
			if (hexFrame == null) {
				hexFrame = new HexFrame(proj, getContents());
				hexFrame.addWindowListener(new WindowAdapter() {
					@Override
					public void windowClosed(WindowEvent e) {
						hexFrame = null;
					}
				});
			}
			return hexFrame;
		}
		
		//
		// methods for accessing the write-enable data
		//
		public boolean setClock(Value newClock, Object trigger) {
			return clockState.updateClock(newClock, trigger);
		}

		public void attributeListChanged(AttributeEvent e) { }

		public void attributeValueChanged(AttributeEvent e) {
			AttributeSet attrs = e.getSource();
			BitWidth addrBits = attrs.getValue(Mem.ADDR_ATTR);
			BitWidth dataBits = attrs.getValue(Mem.DATA_ATTR);
			getContents().setDimensions(addrBits.getWidth(), dataBits.getWidth());
		}
	}
	
	public static class Logger extends InstanceLogger {
		@Override
		public Object[] getLogOptions(InstanceState state) {
			int addrBits = state.getAttributeValue(ADDR_ATTR).getWidth();
			if (addrBits >= logOptions.length) addrBits = logOptions.length - 1;
			synchronized(logOptions) {
				Object[] ret = logOptions[addrBits];
				if (ret == null) {
					ret = new Object[1 << addrBits];
					logOptions[addrBits] = ret;
					for (int i = 0; i < ret.length; i++) {
						ret[i] = Integer.valueOf(i);
					}
				}
				return ret;
			}
		}

		@Override
		public String getLogName(InstanceState state, Object option) {
			if (option instanceof Integer) {
				String disp = Strings.get("ramComponent");
				Location loc = state.getInstance().getLocation();
				return disp + loc + "[" + option + "]";
			} else {
				return null;
			}
		}

		@Override
		public Value getLogValue(InstanceState state, Object option) {
			if (option instanceof Integer) {
				MemState s = (MemState) state.getData();
				int addr = ((Integer) option).intValue();
				return Value.createKnown(BitWidth.create(s.getDataBits()),
						s.getContents().get(addr));
			} else {
				return Value.NIL;
			}
		}
	}
}
