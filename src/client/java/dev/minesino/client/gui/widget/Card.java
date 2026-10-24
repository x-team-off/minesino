package dev.minesino.client.gui.widget;

import dev.minesino.client.gui.Icons;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Navigation / game card: icon, title, description, hover lift with accent glow and a launch chip. */
public class Card extends Widget {
	private final String icon, title, description, cta;
	private final Runnable action;
	private final int accent;
	private long pressedAt;

	public Card(String icon, String title, String description, String cta, Runnable action) {
		this.icon = icon;
		this.title = title;
		this.description = description;
		this.cta = cta;
		this.action = action;
		this.accent = Icons.accent(icon);
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (!visible) return;
		updateHover(mx, my, dt);
		float lift = hover * 1.5f;
		float press = Math.max(0, 1 - (System.currentTimeMillis() - pressedAt) / 160f);
		float cy = y - lift + press;
		Ui.glow(g, x, cy + 2, w, h, 6 + 4 * hover, Theme.alpha(Theme.lerp(0xFF000000, accent, hover), 0.35f + 0.25f * hover));
		Ui.panel(g, x, cy, w, h, 7, Theme.lerp(Theme.CARD, Theme.CARD_HI, hover), Theme.lerp(Theme.BORDER, accent, hover * 0.8f));
		Ui.gradient(g, x + 1, cy + 1, w - 2, h * 0.45f, Theme.alpha(accent, 0.10f + 0.10f * hover), 0x00000000);
		boolean compact = h < 50;
		float iconSize = compact ? Math.min(18, h - 10) : Math.min(26, h * 0.36f);
		float iconCx = compact ? x + 6 + iconSize / 2 + 2 : x + 10 + iconSize / 2;
		float iconCy = compact ? cy + h / 2 : cy + 9 + iconSize / 2;
		Ui.circle(g, iconCx, iconCy, iconSize / 2 + 3, Theme.alpha(accent, 0.14f + 0.1f * hover));
		Icons.draw(g, icon, iconCx, iconCy, iconSize);
		if (compact) {
			float tx = x + iconSize + 16;
			Ui.text(g, Ui.fit(title, w - iconSize - 22), tx, cy + h / 2 - (description.isEmpty() ? 4 : 9), Theme.TEXT);
			if (!description.isEmpty()) Ui.text(g, Ui.fit(description, w - iconSize - 22), tx, cy + h / 2 + 1, Theme.MUTED);
		} else {
			Ui.text(g, Ui.fit(title, w - 16), x + 9, cy + iconSize + 16, Theme.TEXT);
			if (!description.isEmpty()) Ui.text(g, Ui.fit(description, w - 16), x + 9, cy + iconSize + 27, Theme.MUTED);
			if (cta != null && h >= 64) {
				float cw = Ui.width(cta) + 12;
				Ui.round(g, x + w - cw - 7, cy + 8, cw, 12, 6, Theme.alpha(accent, 0.2f + 0.6f * hover));
				Ui.center(g, cta, x + w - cw / 2 - 7, cy + 10, hover > 0.5f ? 0xFF10070A : Theme.TEXT);
			}
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!visible || button != Ui.LEFT || !isOver(mx, my)) return false;
		pressedAt = System.currentTimeMillis();
		Ui.clickSound();
		action.run();
		return true;
	}
}
