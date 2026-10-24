package dev.minesino.client.gui;

import dev.minesino.client.ClientSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.renderer.RenderPipelines;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.resources.Identifier;

/**
 * Drawing primitives of the MineSino design system. Shapes are rasterised at physical window
 * resolution (the pose is scaled down by the GUI scale), so corners and circles stay smooth at any
 * "GUI scale" setting instead of looking like chunky Minecraft pixels. Coordinates are GUI units
 * (floats) like the rest of the screen code.
 */
public final class Ui {
	/** Mouse button codes of 26.3 (SDL numbering: left = 1, right = 3), not the old GLFW 0/1. */
	public static final int LEFT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT;
	public static final int RIGHT = com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_RIGHT;

	private Ui() {}

	public static Minecraft mc() {
		return Minecraft.getInstance();
	}

	public static Font font() {
		return mc().font;
	}

	public static int scale() {
		return Math.max(1, mc().getWindow().getGuiScale());
	}

	public static String tr(String key, Object... args) {
		return I18n.get(key, args);
	}

	private static int px(float v, int s) {
		return Math.round(v * s);
	}

	// ------------------------------------------------------------------ shapes

	public static void rect(GuiGraphicsExtractor g, float x, float y, float w, float h, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		int s = scale();
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		g.fill(px(x, s), px(y, s), px(x + w, s), px(y + h, s), color);
		g.pose().popMatrix();
	}

	public static void gradient(GuiGraphicsExtractor g, float x, float y, float w, float h, int top, int bottom) {
		if (w <= 0 || h <= 0) return;
		int s = scale();
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		g.fillGradient(px(x, s), px(y, s), px(x + w, s), px(y + h, s), top, bottom);
		g.pose().popMatrix();
	}

	/** Horizontal gradient approximated with vertical bands (cheap: max 24 bands). */
	public static void hGradient(GuiGraphicsExtractor g, float x, float y, float w, float h, int left, int right) {
		int bands = Math.clamp((int) (w / 3), 2, 24);
		float bw = w / bands;
		for (int i = 0; i < bands; i++) rect(g, x + i * bw, y, bw + 0.34f, h, Theme.lerp(left, right, (i + 0.5f) / bands));
	}

	/** Filled rounded rectangle with anti-aliased corners. */
	public static void round(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int color) {
		if (w <= 0 || h <= 0 || (color >>> 24) == 0) return;
		UiTextures.ensure();
		int s = scale();
		int X = px(x, s), Y = px(y, s), W = px(x + w, s) - X, H = px(y + h, s) - Y;
		int R = Math.min(px(r, s), Math.min(W, H) / 2);
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		if (R <= 0) {
			g.fill(X, Y, X + W, Y + H, color);
		} else {
			int half = UiTextures.SIZE / 2;
			Identifier t = UiTextures.CIRCLE;
			g.blit(RenderPipelines.GUI_TEXTURED, t, X, Y, 0, 0, R, R, half, half, UiTextures.SIZE, UiTextures.SIZE, color);
			g.blit(RenderPipelines.GUI_TEXTURED, t, X + W - R, Y, half, 0, R, R, half, half, UiTextures.SIZE, UiTextures.SIZE, color);
			g.blit(RenderPipelines.GUI_TEXTURED, t, X, Y + H - R, 0, half, R, R, half, half, UiTextures.SIZE, UiTextures.SIZE, color);
			g.blit(RenderPipelines.GUI_TEXTURED, t, X + W - R, Y + H - R, half, half, R, R, half, half, UiTextures.SIZE, UiTextures.SIZE, color);
			g.fill(X + R, Y, X + W - R, Y + H, color);
			g.fill(X, Y + R, X + R, Y + H - R, color);
			g.fill(X + W - R, Y + R, X + W, Y + H - R, color);
		}
		g.pose().popMatrix();
	}

	/** Rounded rectangle with a 1-physical-pixel-ish border. */
	public static void panel(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int fill, int border) {
		float b = Math.max(1f / scale(), 0.5f);
		round(g, x, y, w, h, r, border);
		round(g, x + b, y + b, w - 2 * b, h - 2 * b, Math.max(0, r - b), fill);
	}

	public static void circle(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
		round(g, cx - radius, cy - radius, radius * 2, radius * 2, radius, color);
	}

	public static void ring(GuiGraphicsExtractor g, float cx, float cy, float radius, int color) {
		UiTextures.ensure();
		int s = scale();
		int X = px(cx - radius, s), Y = px(cy - radius, s), D = px(radius * 2, s);
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		g.blit(RenderPipelines.GUI_TEXTURED, UiTextures.RING, X, Y, 0, 0, D, D, UiTextures.SIZE, UiTextures.SIZE, UiTextures.SIZE, UiTextures.SIZE, color);
		g.pose().popMatrix();
	}

	/**
	 * Soft glow/shadow around a rectangle using a blurred nine-slice. {@code spread} is the blur
	 * radius in GUI units.
	 */
	public static void glow(GuiGraphicsExtractor g, float x, float y, float w, float h, float spread, int color) {
		if ((color >>> 24) == 0 || spread <= 0) return;
		if (ClientSettings.get().reducedEffects) spread *= 0.5f;
		UiTextures.ensure();
		int s = scale();
		int X = px(x - spread, s), Y = px(y - spread, s), X2 = px(x + w + spread, s), Y2 = px(y + h + spread, s);
		int Sp = Math.max(1, px(spread, s));
		int W = X2 - X, H = Y2 - Y;
		if (W < 2 * Sp || H < 2 * Sp) return;
		int half = UiTextures.SIZE / 2;
		Identifier t = UiTextures.SOFT;
		int T = UiTextures.SIZE;
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X, Y, 0, 0, Sp, Sp, half, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X2 - Sp, Y, half, 0, Sp, Sp, half, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X, Y2 - Sp, 0, half, Sp, Sp, half, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X2 - Sp, Y2 - Sp, half, half, Sp, Sp, half, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X + Sp, Y, half, 0, W - 2 * Sp, Sp, 1, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X + Sp, Y2 - Sp, half, half, W - 2 * Sp, Sp, 1, half, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X, Y + Sp, 0, half, Sp, H - 2 * Sp, half, 1, T, T, color);
		g.blit(RenderPipelines.GUI_TEXTURED, t, X2 - Sp, Y + Sp, half, half, Sp, H - 2 * Sp, half, 1, T, T, color);
		g.fill(X + Sp, Y + Sp, X2 - Sp, Y2 - Sp, color);
		g.pose().popMatrix();
	}

	/** Radial glow around a circle (wheels, coins, balls). */
	public static void circleGlow(GuiGraphicsExtractor g, float cx, float cy, float radius, float spread, int color) {
		if ((color >>> 24) == 0) return;
		if (ClientSettings.get().reducedEffects) spread *= 0.5f;
		UiTextures.ensure();
		int s = scale();
		float r = radius + spread;
		int X = px(cx - r, s), Y = px(cy - r, s), D = px(r * 2, s);
		g.pose().pushMatrix();
		g.pose().scale(1f / s, 1f / s);
		g.blit(RenderPipelines.GUI_TEXTURED, UiTextures.SOFT, X, Y, 0, 0, D, D, UiTextures.SIZE, UiTextures.SIZE, UiTextures.SIZE, UiTextures.SIZE, color);
		g.pose().popMatrix();
	}

	/** Card surface: shadow, fill, border and a subtle top highlight. */
	public static void card(GuiGraphicsExtractor g, float x, float y, float w, float h, float r, int fill, int border) {
		glow(g, x, y + 1.5f, w, h, 6, 0x55000000);
		panel(g, x, y, w, h, r, fill, border);
		gradient(g, x + r, y + 0.75f, w - 2 * r, 1.25f, 0x22FFFFFF, 0x00FFFFFF);
	}

	// ------------------------------------------------------------------ text

	public static int width(String s) {
		return font().width(s);
	}

	public static void text(GuiGraphicsExtractor g, String s, float x, float y, int color) {
		g.text(font(), s, Math.round(x), Math.round(y), color, false);
	}

	public static void textShadow(GuiGraphicsExtractor g, String s, float x, float y, int color) {
		g.text(font(), s, Math.round(x), Math.round(y), color, true);
	}

	public static void center(GuiGraphicsExtractor g, String s, float cx, float y, int color) {
		text(g, s, cx - width(s) / 2f, y, color);
	}

	public static void right(GuiGraphicsExtractor g, String s, float rx, float y, int color) {
		text(g, s, rx - width(s), y, color);
	}

	/** Text scaled by {@code k} with its top-left at (x, y). Fractional positions are kept. */
	public static void scaled(GuiGraphicsExtractor g, String s, float x, float y, float k, int color, boolean shadow) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(k, k);
		g.text(font(), s, 0, 0, color, shadow);
		g.pose().popMatrix();
	}

	public static void scaledCenter(GuiGraphicsExtractor g, String s, float cx, float y, float k, int color, boolean shadow) {
		scaled(g, s, cx - width(s) * k / 2f, y, k, color, shadow);
	}

	/** Cuts text to fit {@code maxWidth}, adding an ellipsis. */
	public static String fit(String s, float maxWidth) {
		if (width(s) <= maxWidth) return s;
		String e = "…";
		return font().plainSubstrByWidth(s, (int) Math.max(0, maxWidth - width(e))) + e;
	}

	/** Gradient-filled logo text with neon glow. */
	public static void logo(GuiGraphicsExtractor g, float x, float y, float k) {
		String a = "Mine", b = "Sino";
		float glowA = 0.25f + 0.25f * Anim.pulse(3f);
		scaled(g, a + b, x + 0.6f, y + 0.6f, k, Theme.alpha(Theme.ACCENT, glowA), false);
		scaled(g, a, x, y, k, Theme.TEXT, false);
		scaled(g, b, x + width(a) * k, y, k, Theme.GOLD, false);
	}

	public static boolean inside(double mx, double my, float x, float y, float w, float h) {
		return mx >= x && my >= y && mx < x + w && my < y + h;
	}

	public static void item(GuiGraphicsExtractor g, net.minecraft.world.item.ItemStack stack, float x, float y, float size) {
		g.pose().pushMatrix();
		g.pose().translate(x, y);
		g.pose().scale(size / 16f, size / 16f);
		g.item(stack, 0, 0);
		g.pose().popMatrix();
	}

	public static void clickSound() {
		if (!ClientSettings.get().sounds) return;
		mc().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
			net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK.value(), 1.0f, 0.6f));
	}

	public static void sound(net.minecraft.sounds.SoundEvent e, float pitch, float volume) {
		if (!ClientSettings.get().sounds) return;
		mc().getSoundManager().play(net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(e, pitch, volume));
	}
}
