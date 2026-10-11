package dev.minesino.client.gui.games;

import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Draws playing cards. Cards are ints 0..51 (rank = c % 13, suit = c / 13), -1 = face down. */
final class CardPainter {
	private static final String[] RANKS = {"A", "2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K"};
	private static final String[] SUITS = {"♠", "♥", "♦", "♣"};

	private CardPainter() {}

	static String rank(int card) {
		return RANKS[card % 13];
	}

	static void draw(GuiGraphicsExtractor g, int card, float x, float y, float w, float h, float flip) {
		// flip: 0 = back fully visible, 1 = face fully visible (scaled horizontally for a turn animation)
		float sx = Math.abs(flip * 2 - 1);
		boolean face = flip >= 0.5f && card >= 0;
		g.pose().pushMatrix();
		g.pose().translate(x + w / 2, y + h / 2);
		g.pose().scale(Math.max(0.02f, sx), 1);
		float l = -w / 2, t = -h / 2;
		Ui.glow(g, l, t + 1, w, h, 4, 0x66000000);
		if (face) {
			Ui.panel(g, l, t, w, h, 3, 0xFFFBF8F1, 0xFFB8B2A6);
			int suit = card / 13;
			int color = suit == 1 || suit == 2 ? 0xFFC81E1E : 0xFF15121A;
			Ui.text(g, rank(card), l + 3, t + 3, color);
			Ui.text(g, SUITS[suit], l + 3, t + 12, color);
			Ui.scaledCenter(g, SUITS[suit], 0, -6, 1.6f, color, false);
		} else {
			Ui.panel(g, l, t, w, h, 3, Theme.PURPLE_DARK, Theme.GOLD_DARK);
			Ui.panel(g, l + 3, t + 3, w - 6, h - 6, 2, 0xFF3B1E73, Theme.alpha(Theme.GOLD, 0.6f));
			Ui.logo(g, -Ui.width("MineSino") * 0.3f, -3, 0.6f);
		}
		g.pose().popMatrix();
	}
}
