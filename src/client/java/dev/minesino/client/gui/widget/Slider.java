package dev.minesino.client.gui.widget;

import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import java.util.function.DoubleConsumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Horizontal slider, value 0..1. */
public class Slider extends Widget {
	public double value;
	private boolean dragging;
	public DoubleConsumer onChange = v -> {};
	public int accent = Theme.PURPLE;

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (!visible) return;
		updateHover(mx, my, dt);
		float cy = y + h / 2;
		Ui.round(g, x, cy - 2, w, 4, 2, Theme.BG);
		Ui.round(g, x, cy - 2, (float) (w * value), 4, 2, accent);
		float kx = x + (float) (w * value);
		Ui.glow(g, kx - 4, cy - 4, 8, 8, 3 + 2 * hover, Theme.alpha(accent, 0.5f));
		Ui.circle(g, kx, cy, 4.5f, Theme.TEXT);
		Ui.circle(g, kx, cy, 2.5f, accent);
	}

	private void set(double mx) {
		double v = Math.clamp((mx - x) / Math.max(1, w), 0, 1);
		if (v != value) {
			value = v;
			onChange.accept(v);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (button != Ui.LEFT || !enabled || !Ui.inside(mx, my, x - 4, y, w + 8, h)) return false;
		dragging = true;
		set(mx);
		return true;
	}

	@Override
	public boolean mouseDragged(double mx, double my, int button) {
		if (!dragging) return false;
		set(mx);
		return true;
	}

	@Override
	public boolean mouseReleased(double mx, double my, int button) {
		boolean d = dragging;
		dragging = false;
		return d;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (!isOver(mx, my)) return false;
		value = Math.clamp(value + Math.signum(amount) * 0.01, 0, 1);
		onChange.accept(value);
		return true;
	}
}
