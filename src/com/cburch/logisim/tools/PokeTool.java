/* Copyright (c) 2010, Carl Burch. License information is located in the
 * com.cburch.logisim.Main source code and at www.cburch.com/logisim/. */

package com.cburch.logisim.tools;

import java.awt.*;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;

import javax.swing.Icon;

import com.cburch.logisim.circuit.*;
import com.cburch.logisim.comp.Component;
import com.cburch.logisim.comp.ComponentDrawContext;
import com.cburch.logisim.comp.ComponentUserEvent;
import com.cburch.logisim.data.*;
import com.cburch.logisim.gui.main.Canvas;
import com.cburch.logisim.instance.StdAttr;
import com.cburch.logisim.prefs.AppPreferences;
import com.cburch.logisim.proj.Project;
import com.cburch.logisim.std.wiring.BitLabeledTunnel;
import com.cburch.logisim.util.Icons;

import static com.cburch.logisim.data.Direction.*;

public class PokeTool extends Tool {
	private static final Icon toolIcon = Icons.getIcon("poke.gif");
	private static final Color caretColor = new Color(255, 255, 150);

	private static class WireCaret extends AbstractCaret {
		AttributeSet opts;
		Canvas canvas;
		Wire wire;
		int x;
		int y;

		WireCaret(Canvas c, Wire w, int x, int y, AttributeSet opts) {
			canvas = c;
			wire = w;
			this.x = x;
			this.y = y;
			this.opts = opts;
		}

		@Override
		public void draw(Graphics g) {
			Value v = canvas.getCircuitState().getValue(wire.getEnd0());
			RadixOption radix1 = RadixOption.decode(AppPreferences.POKE_WIRE_RADIX1.get());
			RadixOption radix2 = RadixOption.decode(AppPreferences.POKE_WIRE_RADIX2.get());
			if (radix1 == null) radix1 = RadixOption.RADIX_2;
			String vStr = radix1.toString(v);
			if (radix2 != null && v.getWidth() > 1) {
				vStr += " / " + radix2.toString(v);
			}
			
			FontMetrics fm = g.getFontMetrics();
			g.setColor(caretColor);
			g.fillRect(x + 2, y + 2, fm.stringWidth(vStr) + 4, 
					fm.getAscent() + fm.getDescent() + 4);
			g.setColor(Color.BLACK);
			g.drawRect(x + 2, y + 2, fm.stringWidth(vStr) + 4, 
					fm.getAscent() + fm.getDescent() + 4);
			g.fillOval(x - 2, y - 2, 5, 5);
			g.drawString(vStr, x + 4, y + 4 + fm.getAscent());
		}
	}

    /** Caret tooltip for BitLabeledTunnel: shows value and bit specifications */
    private static class BitTunnelCaret extends AbstractCaret {
        private final Canvas canvas;
        private final Component comp;
        private final int anchorX, anchorY;

        // --- highlight de coincidencias ---
        private static final class LineHit {
            Rectangle r;
            int bitIndex;
            String token;
            LineHit(Rectangle r, int bitIndex, String token) { this.r = r; this.bitIndex = bitIndex; this.token = token; }
        }
        record Row(String text, boolean clickable, String token, int bitIndex) { }

        private final java.util.List<LineHit> hits = new java.util.ArrayList<>();
        private final java.util.List<Component> matched = new java.util.ArrayList<>();
        private Bounds liveBounds = Bounds.EMPTY_BOUNDS;
        private final Rectangle tipRect = new Rectangle();

        BitTunnelCaret(Canvas canvas, Component comp, int x, int y) {
            this.canvas = canvas;
            this.comp = comp;
            this.anchorX = x;
            this.anchorY = y;
        }

        @Override
        public void draw(Graphics g) {
            if (canvas == null || comp == null) return;

            // ---------- 1) Datos del túnel ----------
            AttributeSet atts   = comp.getAttributeSet();
            BitWidth bw         = atts.getValue(StdAttr.WIDTH);
            int w               = Math.max(1, bw == null ? 1 : bw.getWidth());
            boolean outMode     = Boolean.TRUE.equals(atts.getValue(BitLabeledTunnel.ATTR_OUTPUT));
            Direction facing    = atts.getValue(StdAttr.FACING);
            String csv          = atts.getValue(BitLabeledTunnel.BIT_SPECS);
            java.util.List<String> specs = parseSpecs(csv, w);
            String label        = atts.getValue(StdAttr.LABEL);
            if (label == null) label = "";

            // Valor actual en el pin del túnel
            Location pinLoc = comp.getEnd(0).getLocation();
            CircuitState cst = canvas.getCircuitState();
            Value bus = (cst != null) ? cst.getValue(pinLoc) : null;
            if (bus == null || bus == Value.NIL || bus.getWidth() != w) {
                bus = Value.createUnknown(BitWidth.create(w));
            }

            // ---------- 2) Highlight del túnel ----------
            Graphics2D g2 = (Graphics2D) g;
            Stroke oldS = g2.getStroke();
            Color oldC = g2.getColor();
            Composite oldComp = g2.getComposite();

            Bounds b = comp.getBounds(g);
            int hx = b.getX() - 2, hy = b.getY() - 2;
            int hw = b.getWidth() + 4, hh = b.getHeight() + 4;

            // halo translúcido
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.20f));
            g2.setColor(outMode ? new Color(80,140,255) : new Color(80,200,120));
            g2.fillRoundRect(hx, hy, hw, hh, 10, 10);

            // borde resaltado
            g2.setComposite(AlphaComposite.SrcOver);
            g2.setStroke(new BasicStroke(3f));
            g2.setColor(outMode ? new Color(30,90,220) : new Color(40,150,90));
            g2.drawRoundRect(hx, hy, hw, hh, 10, 10);

            g2.setStroke(oldS);
            g2.setColor(oldC);
            g2.setComposite(oldComp);

            // ---------- 3) Construcción de filas con metadatos ----------

            java.util.List<Row> rows = new java.util.ArrayList<>();

            rows.add(new Row(Strings.get("BLTname") + " " + (!label.isBlank() ? label : "not labeled"),
                    false, "", -1));
            rows.add(new Row(Strings.get("BLTmode") + " " + (outMode ? "[OUTPUT]" : "[INPUT]") + ", "
                    + Strings.get("BLTwidth") + " = " + w,
                    false, "", -1));
            rows.add(new Row(Strings.get("BLTvalue") + " " + toBinStringMSBFirst(bus),
                    false, "", -1));

            // Warning opcional (NO clickeable)
            int csvCount = (csv == null || csv.isBlank()) ? 0 : csv.split(",").length;
            if (csvCount > 0 && csvCount != w) {
                rows.add(new Row("WARN: specs vs width: " + csvCount + " != " + w, false, "", -1));
            }

            // Título de sección
            rows.add(new Row(Strings.get("BLTbitdefs"), false, "", -1));

            // Filas por bit (SÍ clickeables)
            for (int i = 0; i < w; i++) {
                String spec  = specs.get(i);             // crudo (0/1/x/N…)
                String pSpec = prettySpec(spec);         // legible
                String bitv  = bitChar(bus.get(i));
                String tok   = norm(spec);               // normalizado para matching
                String line  = "  b" + i + ": " + pSpec + " -> " + bitv;
                rows.add(new Row(line, true, tok, i));
            }

            // ---------- 4) Medidas del cuadro ----------
            Font oldF = g.getFont();
            g.setFont(new Font("monospaced", Font.PLAIN, 12));
            FontMetrics fm = g.getFontMetrics();
            int textW = 0;
            for (Row r : rows) textW = Math.max(textW, fm.stringWidth(r.text));
            int textH = fm.getAscent() + fm.getDescent();

            final int PAD = 6;
            int boxW = textW + PAD * 2;
            int boxH = (textH * rows.size()) + PAD * 2;

            // ---------- 5) Posicionamiento dentro del viewport ----------
            final int GAP = 12;  // separación del componente
            int bx, by;

            if (anchorX != Integer.MIN_VALUE && anchorY != Integer.MIN_VALUE) {
                bx = anchorX + GAP;
                by = anchorY + GAP;
            } else {
                if (facing.equals(EAST)) {
                    bx = b.getX() + b.getWidth() + GAP;
                    by = b.getY();
                } else if (facing.equals(WEST)) {
                    bx = b.getX() - (boxW + GAP);
                    by = b.getY();
                } else if (facing.equals(NORTH)) {
                    bx = b.getX();
                    by = b.getY() - (boxH + GAP);
                } else { // SOUTH
                    bx = b.getX();
                    by = b.getY() + b.getHeight() + GAP;
                }
            }

            // Rectángulo visible de ESTA pasada de pintura
            java.awt.Rectangle clip = g.getClipBounds();
            if (clip == null || clip.width <= 0 || clip.height <= 0) {
                clip = canvas.getVisibleRect();
                if (clip == null || clip.width <= 0 || clip.height <= 0) {
                    clip = new java.awt.Rectangle(0, 0, canvas.getWidth(), canvas.getHeight());
                }
            }

            // Intento de “flip” si no cabe, luego clamp
            final int MARGIN = 4;
            int clipMinX = clip.x + MARGIN;
            int clipMinY = clip.y + MARGIN;
            int clipMaxX = clip.x + clip.width  - MARGIN;
            int clipMaxY = clip.y + clip.height - MARGIN;

            // Flip horizontal si choca por la derecha o se sale por la izquierda
            if (bx + boxW > clipMaxX) {
                int alt = b.getX() - (boxW + GAP);
                if (alt >= clipMinX) bx = alt;
            } else if (bx < clipMinX) {
                int alt = b.getX() + b.getWidth() + GAP;
                if (alt + boxW <= clipMaxX) bx = alt;
            }

            // Flip vertical si choca por abajo o se sale por arriba
            if (by + boxH > clipMaxY) {
                int alt = b.getY() - (boxH + GAP);
                if (alt >= clipMinY) by = alt;
            } else if (by < clipMinY) {
                int alt = b.getY() + b.getHeight() + GAP;
                if (alt + boxH <= clipMaxY) by = alt;
            }

            // Clamp final
            if (bx + boxW > clipMaxX) bx = clipMaxX - boxW;
            if (by + boxH > clipMaxY) by = clipMaxY - boxH;
            if (bx < clipMinX) bx = clipMinX;
            if (by < clipMinY) by = clipMinY;

            // ---------- 6) Pintado del tooltip + generación de hit-boxes ----------
            hits.clear();

            g.setColor(caretColor);
            g.fillRoundRect(bx, by, boxW, boxH, 8, 8);
            g.setColor(Color.DARK_GRAY);
            g.drawRoundRect(bx, by, boxW, boxH, 8, 8);

            int ty = by + PAD + fm.getAscent();
            g.setColor(Color.BLACK);
            for (Row r : rows) {
                g.drawString(r.text, bx + PAD, ty);

                if (r.clickable && r.token != null && !r.token.isBlank()) {
                    // rectángulo de click para toda la fila (con coords ya flipp/clampeadas)
                    Rectangle rr = new Rectangle(bx + 2, ty - fm.getAscent(), boxW - 4, textH);
                    hits.add(new LineHit(rr, r.bitIndex, r.token));
                }
                ty += textH;
            }

            g.setFont(oldF);

            // Guarda rect y bounds vivos para el PokeTool
            tipRect.setBounds(bx, by, boxW, boxH);
            Bounds tipB = Bounds.create(bx, by, boxW, boxH);
            Bounds compB = comp.getBounds(g);
            liveBounds = (compB == null) ? tipB : tipB.add(compB).expand(2);

            // --- Pintar halos para todos los BLT "matched" (si hay) ---
            if (!matched.isEmpty()) {
                Graphics2D g2m = (Graphics2D) g;
                Stroke oldS2 = g2m.getStroke();
                Color oldC2 = g2m.getColor();
                Composite oldCp2 = g2m.getComposite();

                g2m.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 0.18f));
                g2m.setColor(new Color(255, 200, 0, 90));   // relleno ámbar
                for (Component mc : matched) {
                    Bounds mb = mc.getBounds(g);
                    g2m.fillRoundRect(mb.getX()-2, mb.getY()-2, mb.getWidth()+4, mb.getHeight()+4, 10, 10);
                }

                g2m.setComposite(AlphaComposite.SrcOver);
                g2m.setStroke(new BasicStroke(3f));
                g2m.setColor(new Color(255, 170, 0, 220)); // borde ámbar
                for (Component mc : matched) {
                    Bounds mb = mc.getBounds(g);
                    g2m.drawRoundRect(mb.getX()-2, mb.getY()-2, mb.getWidth()+4, mb.getHeight()+4, 10, 10);
                }

                g2m.setStroke(oldS2);
                g2m.setColor(oldC2);
                g2m.setComposite(oldCp2);
            }
        }

        @Override public Bounds getBounds(Graphics g) {
            if (liveBounds != Bounds.EMPTY_BOUNDS) return liveBounds;
            Bounds b = (comp != null) ? comp.getBounds(g) : Bounds.EMPTY_BOUNDS;
            return (b == null) ? Bounds.EMPTY_BOUNDS : b;
        }

        @Override
        public void mousePressed(MouseEvent e) {
            Point p = e.getPoint();
            for (LineHit h : hits) {
                if (h.r.contains(p)) {
                    if (!h.token.isBlank()) highlightMatches(h.token);
                    else clearHighlights();
                    return;
                }
            }
            clearHighlights();
        }

        @Override public void cancelEditing() { clearHighlights(); super.cancelEditing(); }
        @Override public void stopEditing()   { clearHighlights(); super.stopEditing(); }

        // ---- Helpers ----
        private static java.util.List<String> parseSpecs(String csv, int width) {
            java.util.List<String> out = new java.util.ArrayList<>(width);
            if (csv == null || csv.trim().isEmpty()) { for (int i=0;i<width;i++) out.add("x"); return out; }
            String[] toks = csv.split(",");
            for (String t : toks) out.add(t.trim());
            if (out.size() < width) while (out.size() < width) out.add("x");
            else if (out.size() > width) out = out.subList(0, width);
            return out;
        }
        private static String bitChar(Value v) {
            if (v == null) return "X";
            if (v == Value.ERROR) return "E";
            if (v == Value.UNKNOWN) return "X";
            if (v.getWidth() == 1) {
                if (v == Value.TRUE) return "1";
                if (v == Value.FALSE) return "0";
                return v.isFullyDefined() ? (((v.toIntValue() & 1) == 1) ? "1" : "0") : "X";
            }
            return "?";
        }
        private static String toBinStringMSBFirst(Value v) {
            if (v == null) return "(X)";
            if (v.getWidth() <= 1) return (v == Value.TRUE) ? "1" : (v == Value.FALSE ? "0" : "X");
            StringBuilder sb = new StringBuilder(v.getWidth());
            for (int i = v.getWidth() - 1; i >= 0; i--) sb.append(bitChar(v.get(i)));
            return sb.toString();
        }
        private static String prettySpec(String s) {
            if (s == null || s.isBlank() || "x".equalsIgnoreCase(s)) return "X (sin fuente)";
            if ("0".equals(s)) return "Const 0";
            if ("1".equals(s)) return "Const 1";
            if (s.startsWith("N")) return "Net " + s.substring(1);
            return s;
        }

        private static String norm(String t) {
            if (t == null) return "";
            t = t.trim();
            if (t.isEmpty()) return "";
            if ("0".equals(t) || "1".equals(t)) return t;
            if ("x".equalsIgnoreCase(t)) return "x";
            if (t.length()>=2 && (t.charAt(0)=='N' || t.charAt(0)=='n')) {
                try { int id = Integer.parseInt(t.substring(1).trim()); return "N"+id; } catch (NumberFormatException ignore) {}
            }
            return t;
        }

        private void highlightMatches(String token) {
            matched.clear();
            if (token == null || token.isBlank()) {
                canvas.getProject().repaintCanvas();
                return;
            }

            Circuit circ = canvas.getCircuit();
            if (circ == null) return;

            for (Component c : circ.getNonWires()) { // 👈 reemplazo de circ.getComponents()
                if (!(c.getFactory() instanceof BitLabeledTunnel)) continue;
                AttributeSet as = c.getAttributeSet();
                String csv = null;
                try { csv = as.getValue(BitLabeledTunnel.BIT_SPECS); } catch (Throwable ignore) {}
                if (csv == null || csv.isBlank()) continue;

                for (String t : csv.split(",")) {
                    if (norm(t).equals(token)) {
                        matched.add(c);
                        break;
                    }
                }
            }

            canvas.getProject().repaintCanvas();
        }

        private void clearHighlights() {
            if (!matched.isEmpty()) {
                matched.clear();
                canvas.getProject().repaintCanvas();
            }
        }
    }

    private class Listener implements CircuitListener {
		public void circuitChanged(CircuitEvent event) {
			Circuit circ = pokedCircuit;
			if (event.getCircuit() == circ && circ != null
					&& (event.getAction() == CircuitEvent.ACTION_REMOVE
							|| event.getAction() == CircuitEvent.ACTION_CLEAR)
					&& !circ.contains(pokedComponent)) {
				removeCaret(false);
			}
		}
	}

	private static Cursor cursor
		= Cursor.getPredefinedCursor(Cursor.HAND_CURSOR);

	private Listener listener;
	private Circuit pokedCircuit;
	private Component pokedComponent;
	private Caret pokeCaret;

	public PokeTool() {
		this.listener = new Listener();
	}
	
	@Override
	public boolean equals(Object other) {
		return other instanceof PokeTool;
	}
	
	@Override
	public int hashCode() {
		return PokeTool.class.hashCode();
	}

	@Override
	public String getName() {
		return "Poke Tool";
	}

	@Override
	public String getDisplayName() {
		return Strings.get("pokeTool");
	}
	
	private void removeCaret(boolean normal) {
		Circuit circ = pokedCircuit;
		Caret caret = pokeCaret;
		if (caret != null) {
			if (normal) caret.stopEditing(); else caret.cancelEditing();
			circ.removeCircuitListener(listener);
			pokedCircuit = null;
			pokedComponent = null;
			pokeCaret = null;
		}
	}

	private void setPokedComponent(Circuit circ, Component comp, Caret caret) {
		removeCaret(true);
		pokedCircuit = circ;
		pokedComponent = comp;
		pokeCaret = caret;
		if (caret != null) {
			circ.addCircuitListener(listener);
		}
	}

	@Override
	public String getDescription() {
		return Strings.get("pokeToolDesc");
	}

	@Override
	public void draw(Canvas canvas, ComponentDrawContext context) {
		if (pokeCaret != null) pokeCaret.draw(context.getGraphics());
	}

	@Override
	public void deselect(Canvas canvas) {
		removeCaret(true);
		canvas.setHighlightedWires(WireSet.EMPTY);
	}

	@Override
	public void mousePressed(Canvas canvas, Graphics g, MouseEvent e) {
		int x = e.getX();
		int y = e.getY();
		Location loc = Location.create(x, y);
		boolean dirty = false;
		canvas.setHighlightedWires(WireSet.EMPTY);
		if (pokeCaret != null && !pokeCaret.getBounds(g).contains(loc)) {
			dirty = true;
			removeCaret(true);
		}
		if (pokeCaret == null) {
			ComponentUserEvent event = new ComponentUserEvent(canvas, x, y);
			Circuit circ = canvas.getCircuit();
            for (Component c : circ.getAllContaining(loc, g)) {
                if (pokeCaret != null) break;

                if (c instanceof Wire) {
                    Caret caret = new WireCaret(canvas, (Wire) c, x, y,
                            canvas.getProject().getOptions().getAttributeSet());
                    setPokedComponent(circ, c, caret);
                    canvas.setHighlightedWires(circ.getWireSet((Wire) c));
                } else if (c.getFactory() instanceof BitLabeledTunnel) {
                    // Tooltip especializado para BitLabeledTunnel
                    Caret caret = new BitTunnelCaret(canvas, c, x, y);
                    setPokedComponent(circ, c, caret);
                } else {
                    Pokable p = (Pokable) c.getFeature(Pokable.class);
                    if (p != null) {
                        Caret caret = p.getPokeCaret(event);
                        setPokedComponent(circ, c, caret);
                        AttributeSet attrs = c.getAttributeSet();
                        if (attrs != null && !attrs.getAttributes().isEmpty()) {
                            Project proj = canvas.getProject();
                            proj.getFrame().viewComponentAttributes(circ, c);
                        }
                    }
                }
            }
        }
		if (pokeCaret != null) {
			dirty = true;
			pokeCaret.mousePressed(e);
		}
		if (dirty) canvas.getProject().repaintCanvas();
	}

	@Override
	public void mouseDragged(Canvas canvas, Graphics g, MouseEvent e) {
		if (pokeCaret != null) {
			pokeCaret.mouseDragged(e);
			canvas.getProject().repaintCanvas();
		}
	}

	@Override
	public void mouseReleased(Canvas canvas, Graphics g, MouseEvent e) {
		if (pokeCaret != null) {
			pokeCaret.mouseReleased(e);
			canvas.getProject().repaintCanvas();
		}
	}

	@Override
	public void keyTyped(Canvas canvas, KeyEvent e) {
		if (pokeCaret != null) {
			pokeCaret.keyTyped(e);
			canvas.getProject().repaintCanvas();
		}
	}

	@Override
	public void keyPressed(Canvas canvas, KeyEvent e) {
		if (pokeCaret != null) {
			pokeCaret.keyPressed(e);
			canvas.getProject().repaintCanvas();
		}
	}

	@Override
	public void keyReleased(Canvas canvas, KeyEvent e) {
		if (pokeCaret != null) {
			pokeCaret.keyReleased(e);
			canvas.getProject().repaintCanvas();
		}
	}

	@Override
	public void paintIcon(ComponentDrawContext c, int x, int y) {
		Graphics g = c.getGraphics();
		if (toolIcon != null) {
			toolIcon.paintIcon(c.getDestination(), g, x + 2, y + 2);
		} else {
			g.setColor(java.awt.Color.black);
			g.drawLine(x + 4, y +  2, x + 4, y + 17);
			g.drawLine(x + 4, y + 17, x + 1, y + 11);
			g.drawLine(x + 4, y + 17, x + 7, y + 11);

			g.drawLine(x + 15, y +  2, x + 15, y + 17);
			g.drawLine(x + 15, y +  2, x + 12, y + 8);
			g.drawLine(x + 15, y +  2, x + 18, y + 8);
		}
	}

	@Override
	public Cursor getCursor() { return cursor; }
}

