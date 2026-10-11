package dev.minesino.client.gui;

import dev.minesino.MineSino;
import java.util.HashMap;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.resources.Identifier;

/**
 * Generates anti-aliased wheel textures (roulette wheel, fortune wheel) with equal coloured sectors,
 * a gold rim and a dark hub, and draws them rotated. Sector 0 is centred at the top (12 o'clock),
 * sectors follow clockwise.
 */
public final class RouletteWheelTexture {
	public static final int SIZE = 256;
	private static final Map<String, Identifier> CACHE = new HashMap<>();

	/** European wheel pocket order, clockwise starting from 0. */
	public static final int[] EUROPEAN_ORDER = {0, 32, 15, 19, 4, 21, 2, 25, 17, 34, 6, 27, 13, 36, 11, 30, 8, 23, 10, 5, 24,
		16, 33, 1, 20, 14, 31, 9, 22, 18, 29, 7, 28, 12, 35, 3, 26};

	private RouletteWheelTexture() {}

	static void invalidate() {
		CACHE.clear();
	}

	public static Identifier get(String key, int[] sectorColors) {
		return CACHE.computeIfAbsent(key, k -> {
			Identifier id = Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "dynamic/wheel_" + k);
			int n = sectorColors.length;
			double c = SIZE / 2.0;
			UiTextures.register(id, SIZE, (x, y) -> {
				double dx = x + 0.5 - c, dy = y + 0.5 - c;
				double d = Math.hypot(dx, dy);
				double rOuter = c - 1;
				if (d > rOuter + 1) return 0;
				double edge = Math.clamp(rOuter - d + 0.5, 0, 1);
				int rgb;
				if (d > rOuter - 9) {
					// gold rim with a soft bevel
					double t = (d - (rOuter - 9)) / 9;
					rgb = mix(0xF5C451, 0x9C6B1A, Math.abs(t - 0.4) * 1.4);
				} else if (d < c * 0.30) {
					double t = d / (c * 0.30);
					rgb = d < c * 0.27 ? mix(0x2A2338, 0x15111D, t) : 0xC9952B;
				} else {
					double ang = Math.atan2(dx, -dy); // 0 at 12 o'clock, clockwise
					if (ang < 0) ang += Math.PI * 2;
					double sectorF = ang / (Math.PI * 2) * n + 0.5;
					int sector = ((int) Math.floor(sectorF)) % n;
					rgb = sectorColors[sector] & 0xFFFFFF;
					double frac = sectorF - Math.floor(sectorF);
					// thin gold separators
					double sepPx = Math.min(frac, 1 - frac) * (Math.PI * 2 * d / n);
					if (sepPx < 0.9) rgb = mix(0xD4A640, rgb, sepPx / 0.9);
					// inner pocket ring
					if (d < c * 0.62 && d > c * 0.60) rgb = 0xD4A640;
					double shade = 0.82 + 0.18 * (d / rOuter);
					rgb = scale(rgb, shade);
				}
				return UiTextures.argb(edge, rgb);
			});
			return id;
		});
	}

	private static int mix(int a, int b, double t) {
		t = Math.clamp(t, 0, 1);
		int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
		int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
		int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
		return (r << 16) | (g << 8) | bl;
	}

	private static int scale(int rgb, double k) {
		int r = (int) Math.min(255, ((rgb >> 16) & 255) * k);
		int g = (int) Math.min(255, ((rgb >> 8) & 255) * k);
		int b = (int) Math.min(255, (rgb & 255) * k);
		return (r << 16) | (g << 8) | b;
	}

	/** Draws the wheel centred at (cx, cy) with the given radius, rotated by {@code angle} radians. */
	public static void draw(GuiGraphicsExtractor g, Identifier tex, float cx, float cy, float radius, float angle) {
		int s = Ui.scale();
		int D = Math.round(radius * 2 * s);
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(angle);
		g.pose().scale(1f / s, 1f / s);
		g.blit(RenderPipelines.GUI_TEXTURED, tex, -D / 2, -D / 2, 0, 0, D, D, SIZE, SIZE, SIZE, SIZE, 0xFFFFFFFF);
		g.pose().popMatrix();
	}
}
