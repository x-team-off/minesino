package dev.minesino.client.gui;

import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.textures.FilterMode;
import dev.minesino.MineSino;
import java.util.function.IntBinaryOperator;
import net.minecraft.client.Minecraft;
import net.minecraft.client.renderer.texture.DynamicTexture;
import net.minecraft.resources.Identifier;

/**
 * Procedurally generated, anti-aliased textures used by the design system. They are created once on
 * first use, sampled with linear filtering (smooth edges at any GUI scale) and released when the
 * client resources are reloaded.
 */
public final class UiTextures {
	public static final int SIZE = 64;
	public static final Identifier CIRCLE = Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "dynamic/circle");
	public static final Identifier SOFT = Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "dynamic/soft");
	public static final Identifier RING = Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "dynamic/ring");
	private static boolean ready;

	private UiTextures() {}

	/** Linear filtered dynamic texture. */
	static final class SmoothTexture extends DynamicTexture {
		SmoothTexture(String label, NativeImage image) {
			super(() -> label, image);
			this.sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR);
		}
	}

	public static void ensure() {
		if (ready) return;
		ready = true;
		register(CIRCLE, SIZE, (x, y) -> {
			double d = Math.hypot(x + 0.5 - SIZE / 2.0, y + 0.5 - SIZE / 2.0);
			double a = Math.clamp(SIZE / 2.0 - d, 0, 1);
			return argb(a, 0xFFFFFF);
		});
		register(SOFT, SIZE, (x, y) -> {
			double d = Math.hypot(x + 0.5 - SIZE / 2.0, y + 0.5 - SIZE / 2.0) / (SIZE / 2.0);
			double t = Math.clamp(1 - d, 0, 1);
			return argb(t * t * (3 - 2 * t), 0xFFFFFF);
		});
		register(RING, SIZE, (x, y) -> {
			double d = Math.hypot(x + 0.5 - SIZE / 2.0, y + 0.5 - SIZE / 2.0);
			double outer = Math.clamp(SIZE / 2.0 - d, 0, 1);
			double inner = Math.clamp(d - (SIZE / 2.0 - 4), 0, 1);
			return argb(Math.min(outer, inner), 0xFFFFFF);
		});
	}

	public static void register(Identifier id, int size, IntBinaryOperator pixel) {
		register(id, size, size, pixel);
	}

	public static void register(Identifier id, int w, int h, IntBinaryOperator pixel) {
		NativeImage img = new NativeImage(w, h, false);
		for (int y = 0; y < h; y++) {
			for (int x = 0; x < w; x++) img.setPixel(x, y, pixel.applyAsInt(x, y));
		}
		Minecraft.getInstance().getTextureManager().register(id, new SmoothTexture(id.toString(), img));
	}

	public static int argb(double alpha, int rgb) {
		int a = (int) Math.round(Math.clamp(alpha, 0, 1) * 255);
		return (a << 24) | (rgb & 0xFFFFFF);
	}

	/** Forget generated textures (the texture manager drops them on resource reload). */
	public static void invalidate() {
		ready = false;
		RouletteWheelTexture.invalidate();
	}
}
