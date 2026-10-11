package dev.minesino.client.gui;

import dev.minesino.client.gui.widget.Button;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Confirmation dialog drawn over the current page. */
public final class Modal {
	public record Line(String label, String value, int color, ItemStack icon) {
		public static Line of(String label, String value) {
			return new Line(label, value, Theme.TEXT, ItemStack.EMPTY);
		}
	}

	private final String title;
	private final List<Line> lines;
	private final Button confirm, cancel;
	private final long opened = Anim.now();
	boolean closed;
	private float x, y, w, h;

	public Modal(String title, List<Line> lines, String confirmLabel, Button.Style style, Runnable onConfirm, Runnable onCancel) {
		this.title = title;
		this.lines = new ArrayList<>(lines);
		this.confirm = Button.of(confirmLabel, style, () -> {
			closed = true;
			onConfirm.run();
		});
		this.cancel = Button.of(Ui.tr("minesino.ui.cancel"), Button.Style.GHOST, () -> {
			closed = true;
			if (onCancel != null) onCancel.run();
		});
	}

	public void cancel() {
		cancel.mouseClicked(cancel.x + 1, cancel.y + 1, Ui.LEFT);
	}

	void layout(float sw, float sh) {
		w = Math.min(sw - 20, 240);
		h = 46 + lines.size() * 13 + 24;
		x = (sw - w) / 2;
		y = (sh - h) / 2;
		float bw = (w - 30) / 2;
		cancel.bounds(x + 10, y + h - 28, bw, 18);
		confirm.bounds(x + 20 + bw, y + h - 28, bw, 18);
	}

	void render(GuiGraphicsExtractor g, int mx, int my, float dt, float sw, float sh) {
		layout(sw, sh);
		float t = Anim.easeOutBack(Anim.progress(opened, 220));
		Ui.rect(g, 0, 0, sw, sh, Theme.alpha(0x99000000, Math.min(1, t)));
		g.pose().pushMatrix();
		float cx = x + w / 2, cy = y + h / 2;
		float k = 0.92f + 0.08f * t;
		g.pose().translate(cx, cy);
		g.pose().scale(k, k);
		g.pose().translate(-cx, -cy);
		Ui.glow(g, x, y, w, h, 14, Theme.alpha(Theme.PURPLE, 0.35f));
		Ui.panel(g, x, y, w, h, 8, Theme.SURFACE, Theme.BORDER_HI);
		Ui.hGradient(g, x + 8, y, w - 16, 1.2f, Theme.PURPLE, Theme.GOLD);
		Ui.scaledCenter(g, title, x + w / 2, y + 10, 1.15f, Theme.TEXT, false);
		float ly = y + 30;
		for (Line l : lines) {
			float lx = x + 12;
			if (!l.icon().isEmpty()) {
				Ui.item(g, l.icon(), lx, ly - 2, 11);
				lx += 14;
			}
			Ui.text(g, Ui.fit(l.label(), w - 110), lx, ly, Theme.TEXT_DIM);
			Ui.right(g, l.value(), x + w - 12, ly, l.color());
			ly += 13;
		}
		cancel.render(g, mx, my, dt);
		confirm.render(g, mx, my, dt);
		g.pose().popMatrix();
	}

	boolean mouseClicked(double mx, double my, int button) {
		if (confirm.mouseClicked(mx, my, button) || cancel.mouseClicked(mx, my, button)) return true;
		return true; // modal swallows clicks
	}
}
