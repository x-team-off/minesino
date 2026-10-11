package dev.minesino.client.gui.page;

import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Card;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Main menu: welcome banner with balance and navigation cards. */
public class HomePage extends Page {
	@Override
	public String title() {
		return "";
	}

	@Override
	protected void layout() {
		record Entry(String icon, String key, Runnable action) {}
		List<Entry> entries = new ArrayList<>();
		var d = screen.data;
		if (d.casinoEnabled) entries.add(new Entry("casino", "casino", () -> screen.navigate(new CasinoPage())));
		if (d.sellEnabled) entries.add(new Entry("sell", "sell", () -> screen.navigate(new SellPage())));
		if (d.shopEnabled) entries.add(new Entry("shop", "shop", () -> screen.navigate(new ShopPage())));
		entries.add(new Entry("history", "history", () -> screen.navigate(new HistoryPage())));
		entries.add(new Entry("stats", "stats", () -> screen.navigate(new StatsPage())));
		entries.add(new Entry("settings", "settings", () -> screen.navigate(new SettingsPage())));
		if (d.admin) entries.add(new Entry("admin", "admin", () -> screen.navigate(new AdminPage())));
		float bannerH = Math.min(56, h * 0.26f);
		float top = y + bannerH + 8;
		int cols = w > 360 ? 3 : 2;
		int rows = (entries.size() + cols - 1) / cols;
		float gap = 6;
		float cw = (w - gap * (cols - 1)) / cols;
		float ch = Math.min(72, (y + h - top - gap * (rows - 1)) / rows);
		for (int i = 0; i < entries.size(); i++) {
			Entry e = entries.get(i);
			int c = i % cols, r = i / cols;
			add(new Card(e.icon(), Ui.tr("minesino.nav." + e.key()), Ui.tr("minesino.nav." + e.key() + ".desc"), null, e.action()))
				.bounds(x + c * (cw + gap), top + r * (ch + gap), cw, ch);
		}
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float bannerH = Math.min(56, h * 0.26f);
		Ui.glow(g, x, y, w, bannerH, 8, 0x55000000);
		Ui.panel(g, x, y, w, bannerH, 8, 0xFF1A1428, Theme.BORDER_HI);
		Ui.hGradient(g, x + 1, y + 1, w - 2, bannerH - 2, Theme.alpha(Theme.PURPLE_DARK, 0.35f), Theme.alpha(Theme.GOLD_DARK, 0.15f));
		// shimmering accent line
		float t = (Anim.now() % 4000) / 4000f;
		Ui.hGradient(g, x + 8 + t * (w - 76), y + bannerH - 1.5f, 60, 1, Theme.alpha(Theme.GOLD, 0f), Theme.alpha(Theme.GOLD, 0.7f));
		float top = y + (bannerH - 30) / 2;
		Ui.text(g, Ui.tr("minesino.home.balance"), x + 12, top, Theme.MUTED);
		String bal = ClientState.balanceLoaded ? ClientState.money(ClientState.balance) : Ui.tr("minesino.hud.loading");
		Ui.scaled(g, Ui.fit(bal, (w * 0.55f) / 1.8f), x + 12, top + 12, 1.8f, Theme.GOLD, true);
		String hello = Ui.tr("minesino.home.welcome", screen.data.playerName);
		Ui.right(g, Ui.fit(hello, w * 0.4f), x + w - 12, top + 2, Theme.TEXT);
		Ui.right(g, Ui.tr("minesino.home.tagline"), x + w - 12, top + 15, Theme.alpha(0xFFB794FF, 0.9f));
	}
}
