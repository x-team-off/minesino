package dev.minesino.client.gui.page;

import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Player statistics (informational only; balance is never derived from it). */
public class StatsPage extends Page {
	private JsonObject stats;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.stats");
	}

	@Override
	protected void layout() {}

	@Override
	public void onShow() {
		request("data", "stats", new JsonObject(), r -> {
			if (r.ok()) stats = r.data();
		});
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (stats == null) {
			Ui.center(g, Ui.tr("minesino.hud.loading"), x + w / 2, y + h / 2, Theme.MUTED);
			return;
		}
		long rounds = Json.getLong(stats, "rounds", 0), wins = Json.getLong(stats, "wins", 0);
		long net = Json.getLong(stats, "net", 0);
		String[][] cells = {
			{"rounds", Long.toString(rounds), ""},
			{"wins", wins + " / " + Json.getLong(stats, "losses", 0) + " / " + Json.getLong(stats, "pushes", 0),
				rounds == 0 ? "" : Ui.tr("minesino.stats.winrate", wins * 100 / rounds)},
			{"wagered", ClientState.money(Json.getLong(stats, "wagered", 0)), ""},
			{"paid_out", ClientState.money(Json.getLong(stats, "paidOut", 0)), ""},
			{"net", ClientState.moneySigned(net), ""},
			{"biggest_win", ClientState.money(Json.getLong(stats, "biggestWin", 0)), ""},
			{"items_sold", Long.toString(Json.getLong(stats, "itemsSold", 0)), ClientState.money(Json.getLong(stats, "itemIncome", 0))},
			{"mobs_sold", Long.toString(Json.getLong(stats, "mobsSold", 0)), ClientState.money(Json.getLong(stats, "mobIncome", 0))},
			{"purchases", Long.toString(Json.getLong(stats, "purchases", 0)), ClientState.money(Json.getLong(stats, "spent", 0))}};
		int cols = 3;
		float gap = 6;
		float cw = (w - gap * (cols - 1)) / cols, ch = Math.min(50, (h - gap * 2) / 3);
		for (int i = 0; i < cells.length; i++) {
			float cx = x + (i % cols) * (cw + gap), cy = y + (i / cols) * (ch + gap);
			String key = cells[i][0];
			int accent = key.equals("net") ? (net >= 0 ? Theme.GREEN : Theme.RED) : key.contains("sold") || key.equals("purchases") ? Theme.NEON : Theme.GOLD;
			Ui.card(g, cx, cy, cw, ch, 7, Theme.CARD, Theme.BORDER);
			Ui.round(g, cx + 6, cy + 7, 2, ch - 14, 1, accent);
			Ui.text(g, Ui.fit(Ui.tr("minesino.stats." + key), cw - 16), cx + 12, cy + 7, Theme.MUTED);
			Ui.scaled(g, Ui.fit(cells[i][1], (cw - 16) / 1.3f), cx + 12, cy + 19, 1.3f, accent, true);
			if (!cells[i][2].isEmpty()) Ui.text(g, Ui.fit(cells[i][2], cw - 16), cx + 12, cy + ch - 12, Theme.TEXT_DIM);
		}
	}
}
