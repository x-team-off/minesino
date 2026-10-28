package dev.minesino.client.gui;

/** MineSino palette: near-black surfaces with neon red and gold accents. */
public final class Theme {
	private Theme() {}

	public static final int BACKDROP = 0xC0090405;
	public static final int BG = 0xFF0A0607;
	public static final int SURFACE = 0xFF120A0C;
	public static final int CARD = 0xFF1A0E11;
	public static final int CARD_HI = 0xFF261217;
	public static final int CARD_PRESS = 0xFF321820;
	public static final int BORDER = 0xFF3A141C;
	public static final int BORDER_HI = 0xFF7A1F2E;
	public static final int ACCENT = 0xFFFF1F3D;
	public static final int ACCENT_DARK = 0xFFB0001C;
	public static final int ACCENT_GLOW = 0x99FF1F3D;
	public static final int GOLD = 0xFFF5C451;
	public static final int GOLD_DARK = 0xFFC9952B;
	public static final int NEON = 0xFF22D3EE;
	public static final int PINK = 0xFFFF2D55;
	public static final int GREEN = 0xFF22C55E;
	public static final int GREEN_DARK = 0xFF15803D;
	public static final int RED = 0xFFEF4444;
	public static final int RED_DARK = 0xFFB91C1C;
	public static final int TEXT = 0xFFF4F1FA;
	public static final int TEXT_DIM = 0xFFCCB1B5;
	public static final int MUTED = 0xFF917579;
	public static final int SHADOW = 0x99000000;

	public static int alpha(int color, float a) {
		int base = (color >>> 24) & 0xFF;
		int na = Math.clamp(Math.round(base * a), 0, 255);
		return (na << 24) | (color & 0xFFFFFF);
	}

	public static int lerp(int c1, int c2, float t) {
		t = Math.clamp(t, 0f, 1f);
		int a = (int) (((c1 >>> 24) & 0xFF) + (((c2 >>> 24) & 0xFF) - ((c1 >>> 24) & 0xFF)) * t);
		int r = (int) (((c1 >> 16) & 0xFF) + (((c2 >> 16) & 0xFF) - ((c1 >> 16) & 0xFF)) * t);
		int g = (int) (((c1 >> 8) & 0xFF) + (((c2 >> 8) & 0xFF) - ((c1 >> 8) & 0xFF)) * t);
		int b = (int) ((c1 & 0xFF) + ((c2 & 0xFF) - (c1 & 0xFF)) * t);
		return (a << 24) | (r << 16) | (g << 8) | b;
	}
}
