package dev.minesino.client.gui.games;

import dev.minesino.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws a die face with pips. */
final class DicePainter {
	private static final float[][][] PIPS = {
		{},
		{{0, 0}},
		{{-1, -1}, {1, 1}},
		{{-1, -1}, {0, 0}, {1, 1}},
		{{-1, -1}, {1, -1}, {-1, 1}, {1, 1}},
		{{-1, -1}, {1, -1}, {0, 0}, {-1, 1}, {1, 1}},
		{{-1, -1}, {1, -1}, {-1, 0}, {1, 0}, {-1, 1}, {1, 1}}};

	private DicePainter() {}

	static void draw(GuiGraphicsExtractor g, int face, float cx, float cy, float size, float rotation, int body, int pip, int border) {
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(rotation);
		float h = size / 2;
		Ui.glow(g, -h, -h + 1, size, size, size * 0.15f, 0x66000000);
		Ui.panel(g, -h, -h, size, size, size * 0.18f, body, border);
		Ui.gradient(g, -h + 1, -h + 1, size - 2, size * 0.35f, 0x22FFFFFF, 0x00FFFFFF);
		for (float[] p : PIPS[Math.clamp(face, 1, 6)]) Ui.circle(g, p[0] * size * 0.27f, p[1] * size * 0.27f, size * 0.085f, pip);
		g.pose().popMatrix();
	}
}
