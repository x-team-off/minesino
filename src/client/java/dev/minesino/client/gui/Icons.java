package dev.minesino.client.gui;

import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Procedurally drawn icons for sections and games, in the MineSino style. */
public final class Icons {
	private Icons() {}

	/** Accent colour per icon id. */
	public static int accent(String id) {
		return switch (id) {
			case "casino", "roulette", "keno" -> Theme.ACCENT;
			case "slots", "coinflip", "shop", "wheel" -> Theme.GOLD;
			case "blackjack", "hilo" -> 0xFF60A5FA;
			case "plinko", "crash" -> Theme.PINK;
			case "dice", "history" -> Theme.NEON;
			case "upgrade", "sell", "stats" -> Theme.GREEN;
			case "mines", "admin" -> Theme.RED;
			default -> Theme.TEXT_DIM;
		};
	}

	public static void draw(GuiGraphicsExtractor g, String id, float cx, float cy, float s) {
		int a = accent(id);
		float u = s / 16f;
		switch (id) {
			case "casino" -> {
				chip(g, cx - 3 * u, cy + 1 * u, 5 * u, Theme.ACCENT);
				chip(g, cx + 3 * u, cy - 2 * u, 5 * u, Theme.GOLD);
			}
			case "roulette" -> {
				Ui.circle(g, cx, cy, 7 * u, Theme.GOLD);
				Ui.circle(g, cx, cy, 6 * u, 0xFF2B1B1D);
				for (int i = 0; i < 8; i++) {
					double ang = i * Math.PI / 4;
					Ui.circle(g, cx + (float) Math.sin(ang) * 4.2f * u, cy - (float) Math.cos(ang) * 4.2f * u, 1.3f * u, i % 2 == 0 ? Theme.RED : 0xFF0B0B0B);
				}
				Ui.circle(g, cx, cy, 1.6f * u, Theme.GOLD);
			}
			case "slots" -> {
				Ui.round(g, cx - 7 * u, cy - 6 * u, 14 * u, 12 * u, 2 * u, Theme.GOLD_DARK);
				for (int i = 0; i < 3; i++) {
					Ui.round(g, cx - 6 * u + i * 4.3f * u, cy - 4.5f * u, 3.6f * u, 9 * u, 1 * u, 0xFFF8F5EE);
					Ui.scaledCenter(g, "7", cx - 4.2f * u + i * 4.3f * u, cy - 2.6f * u, 0.55f * u, Theme.RED, false);
				}
			}
			case "blackjack", "hilo" -> {
				card(g, cx - 4 * u, cy - 1 * u, u, -0.2f, 0xFFF8F5EE, "A", 0xFF111111);
				card(g, cx + 3 * u, cy, u, 0.2f, 0xFFF8F5EE, id.equals("hilo") ? "▲" : "K", Theme.RED);
			}
			case "plinko" -> {
				for (int r = 0; r < 4; r++) {
					for (int c = 0; c <= r; c++) Ui.circle(g, cx + (c - r / 2f) * 3.4f * u, cy - 5 * u + r * 3.2f * u, 0.9f * u, 0xFFE5E7EB);
				}
				Ui.circle(g, cx + 1.7f * u, cy + 6 * u, 1.6f * u, Theme.PINK);
			}
			case "dice" -> {
				Ui.round(g, cx - 6 * u, cy - 6 * u, 12 * u, 12 * u, 2.5f * u, 0xFFF8F5EE);
				pip(g, cx - 3 * u, cy - 3 * u, u);
				pip(g, cx, cy, u);
				pip(g, cx + 3 * u, cy + 3 * u, u);
			}
			case "upgrade" -> {
				Ui.round(g, cx - 1.5f * u, cy - 2 * u, 3 * u, 9 * u, 1 * u, Theme.GREEN);
				for (int i = 0; i < 5; i++) Ui.round(g, cx - (5 - i) * u, cy - 3 * u - i * 1.2f * u, (10 - 2 * i) * u, 1.3f * u, 0.6f * u, Theme.GREEN);
			}
			case "coinflip" -> {
				Ui.circle(g, cx, cy, 7 * u, Theme.GOLD_DARK);
				Ui.circle(g, cx, cy, 6 * u, Theme.GOLD);
				Ui.scaledCenter(g, "$", cx + 0.3f * u, cy - 3.2f * u, 0.8f * u, 0xFF6B4A0E, false);
			}
			case "mines" -> {
				for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) {
					boolean bomb = r == 1 && c == 1;
					Ui.round(g, cx - 6.5f * u + c * 4.5f * u, cy - 6.5f * u + r * 4.5f * u, 3.8f * u, 3.8f * u, 0.8f * u, bomb ? Theme.RED : (r + c) % 2 == 0 ? Theme.GREEN : 0xFF503337);
				}
			}
			case "crash" -> {
				for (int i = 0; i < 12; i++) {
					float t = i / 11f;
					Ui.circle(g, cx - 6 * u + t * 12 * u, cy + 5 * u - t * t * 10 * u, 0.9f * u, Theme.PINK);
				}
				Ui.circle(g, cx + 6 * u, cy - 5 * u, 1.8f * u, Theme.GOLD);
			}
			case "wheel" -> {
				Ui.circle(g, cx, cy, 7 * u, Theme.GOLD);
				int[] cols = {Theme.ACCENT, Theme.PINK, Theme.NEON, Theme.GREEN};
				for (int i = 0; i < 4; i++) {
					double ang = i * Math.PI / 2 + Math.PI / 4;
					Ui.circle(g, cx + (float) Math.sin(ang) * 3.3f * u, cy - (float) Math.cos(ang) * 3.3f * u, 2.6f * u, cols[i]);
				}
				Ui.circle(g, cx, cy, 1.5f * u, 0xFF2B1B1D);
				Ui.round(g, cx - 1 * u, cy - 9 * u, 2 * u, 3 * u, 0.8f * u, Theme.TEXT);
			}
			case "keno" -> {
				for (int r = 0; r < 3; r++) for (int c = 0; c < 4; c++) {
					boolean on = (r * 4 + c) % 5 == 1;
					Ui.circle(g, cx - 5.25f * u + c * 3.5f * u, cy - 3.5f * u + r * 3.5f * u, 1.4f * u, on ? Theme.ACCENT : 0xFF503337);
				}
			}
			case "sell" -> {
				Ui.round(g, cx - 6 * u, cy - 3 * u, 12 * u, 9 * u, 2 * u, 0xFF8B6B3A);
				Ui.round(g, cx - 6 * u, cy - 3 * u, 12 * u, 2.5f * u, 1 * u, 0xFFA8834A);
				Ui.circle(g, cx + 4 * u, cy - 5 * u, 3 * u, Theme.GREEN);
				Ui.scaledCenter(g, "+", cx + 4.2f * u, cy - 7.4f * u, 0.55f * u, 0xFF052E12, false);
			}
			case "shop" -> {
				Ui.round(g, cx - 6 * u, cy - 3 * u, 12 * u, 9 * u, 2 * u, Theme.GOLD_DARK);
				Ui.round(g, cx - 3 * u, cy - 7 * u, 6 * u, 5 * u, 2.5f * u, 0x00000000);
				Ui.ring(g, cx, cy - 3.5f * u, 3.3f * u, Theme.GOLD);
				Ui.round(g, cx - 6 * u, cy - 1 * u, 12 * u, 1.2f * u, 0.5f * u, Theme.GOLD);
			}
			case "history" -> {
				Ui.ring(g, cx, cy, 7 * u, Theme.NEON);
				Ui.round(g, cx - 0.6f * u, cy - 4.5f * u, 1.2f * u, 5 * u, 0.6f * u, Theme.NEON);
				Ui.round(g, cx - 0.6f * u, cy - 0.6f * u, 4 * u, 1.2f * u, 0.6f * u, Theme.NEON);
			}
			case "stats" -> {
				float[] hs = {4, 7, 5, 10};
				for (int i = 0; i < 4; i++) Ui.round(g, cx - 6.5f * u + i * 3.5f * u, cy + 6 * u - hs[i] * u, 2.6f * u, hs[i] * u, 0.8f * u, i == 3 ? Theme.GOLD : Theme.GREEN);
			}
			case "settings" -> {
				Ui.circle(g, cx, cy, 6.5f * u, Theme.TEXT_DIM);
				for (int i = 0; i < 6; i++) {
					double ang = i * Math.PI / 3;
					Ui.circle(g, cx + (float) Math.sin(ang) * 6.5f * u, cy - (float) Math.cos(ang) * 6.5f * u, 1.8f * u, Theme.TEXT_DIM);
				}
				Ui.circle(g, cx, cy, 2.6f * u, Theme.SURFACE);
			}
			case "admin" -> {
				Ui.round(g, cx - 5 * u, cy - 6 * u, 10 * u, 9 * u, 1.5f * u, Theme.RED);
				Ui.round(g, cx - 3 * u, cy + 2 * u, 6 * u, 4 * u, 3 * u, Theme.RED);
				Ui.scaledCenter(g, "!", cx + 0.2f * u, cy - 4.5f * u, 0.7f * u, Theme.TEXT, false);
			}
			default -> Ui.circle(g, cx, cy, 6 * u, a);
		}
	}

	private static void chip(GuiGraphicsExtractor g, float cx, float cy, float r, int color) {
		Ui.circle(g, cx, cy, r, color);
		Ui.ring(g, cx, cy, r * 0.75f, 0xCCFFFFFF);
		Ui.circle(g, cx, cy, r * 0.45f, Theme.lerp(color, 0xFF000000, 0.2f));
	}

	private static void pip(GuiGraphicsExtractor g, float cx, float cy, float u) {
		Ui.circle(g, cx, cy, 1.2f * u, 0xFF1A1A1A);
	}

	private static void card(GuiGraphicsExtractor g, float cx, float cy, float u, float rot, int bg, String txt, int col) {
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().rotate(rot);
		Ui.round(g, -4 * u, -5.5f * u, 8 * u, 11 * u, 1.3f * u, 0xFF9CA3AF);
		Ui.round(g, -3.7f * u, -5.2f * u, 7.4f * u, 10.4f * u, 1.1f * u, bg);
		Ui.scaledCenter(g, txt, 0, -2.6f * u, 0.6f * u, col, false);
		g.pose().popMatrix();
	}
}
