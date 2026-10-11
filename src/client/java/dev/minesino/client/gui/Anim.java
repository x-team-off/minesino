package dev.minesino.client.gui;

import dev.minesino.client.ClientSettings;

/** Frame-rate independent animation helpers. */
public final class Anim {
	private Anim() {}

	public static long now() {
		return System.nanoTime() / 1_000_000L;
	}

	/** Exponential approach towards target; {@code speed} ≈ 1/seconds. */
	public static float approach(float current, float target, float dtSeconds, float speed) {
		if (!ClientSettings.get().animations) return target;
		float k = 1f - (float) Math.exp(-speed * dtSeconds);
		float v = current + (target - current) * k;
		return Math.abs(target - v) < 0.001f ? target : v;
	}

	public static float easeOutCubic(float t) {
		t = Math.clamp(t, 0f, 1f);
		float u = 1 - t;
		return 1 - u * u * u;
	}

	public static float easeInOutCubic(float t) {
		t = Math.clamp(t, 0f, 1f);
		return t < 0.5f ? 4 * t * t * t : 1 - (float) Math.pow(-2 * t + 2, 3) / 2;
	}

	public static float easeOutBack(float t) {
		t = Math.clamp(t, 0f, 1f);
		float c1 = 1.70158f, c3 = c1 + 1;
		return 1 + c3 * (float) Math.pow(t - 1, 3) + c1 * (float) Math.pow(t - 1, 2);
	}

	/** Progress 0..1 of an animation started at {@code startMs} lasting {@code durationMs}. */
	public static float progress(long startMs, long durationMs) {
		if (!ClientSettings.get().animations) return 1f;
		float speed = ClientSettings.get().animationSpeed;
		return Math.clamp((now() - startMs) * speed / (float) Math.max(1, durationMs), 0f, 1f);
	}

	/** Gentle 0..1 pulse for glows. */
	public static float pulse(float periodSeconds) {
		if (!ClientSettings.get().animations) return 0.5f;
		return 0.5f + 0.5f * (float) Math.sin(now() / 1000.0 * Math.PI * 2 / periodSeconds);
	}
}
