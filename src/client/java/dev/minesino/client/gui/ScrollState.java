package dev.minesino.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Smooth vertical scrolling for list areas. */
public final class ScrollState {
	public float offset, target, content, view;

	public void update(float dt) {
		target = Math.clamp(target, 0, Math.max(0, content - view));
		offset += (target - offset) * Math.min(1, dt * 14);
		if (Math.abs(target - offset) < 0.2f) offset = target;
	}

	public void scroll(double amount, float step) {
		target = (float) Math.clamp(target - amount * step, 0, Math.max(0, content - view));
	}

	public void reset() {
		offset = target = 0;
	}

	public void bar(GuiGraphicsExtractor g, float x, float y) {
		if (content <= view) return;
		float h = Math.max(12, view * view / content);
		float pos = (view - h) * (offset / Math.max(1, content - view));
		Ui.round(g, x, y, 2, view, 1, 0x22FFFFFF);
		Ui.round(g, x, y + pos, 2, h, 1, Theme.alpha(Theme.ACCENT, 0.8f));
	}
}
