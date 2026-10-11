package dev.minesino.client.gui.page;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Admin overview (operators only; the server re-checks permissions on every request). */
public class AdminPage extends Page {
	private JsonObject overview;
	private final List<String> messages = new ArrayList<>();

	@Override
	public String title() {
		return Ui.tr("minesino.nav.admin");
	}

	@Override
	protected void layout() {
		add(Button.of(Ui.tr("minesino.admin.reload"), Button.Style.DANGER, () -> request("admin", "reload", new JsonObject(), r -> {
			messages.clear();
			if (r.ok()) {
				messages.add(Ui.tr("minesino.admin.reloaded", Json.getInt(r.data(), "warnings", 0)));
				for (JsonElement e : r.data().getAsJsonArray("messages")) messages.add(e.getAsString());
				ClientState.toast(Ui.tr("minesino.admin.reload"), Ui.tr("minesino.admin.done"), Theme.GREEN);
				load();
			}
		})).when(() -> !busy)).bounds(x, y + 70, 150, 18);
	}

	@Override
	public void onShow() {
		load();
	}

	private void load() {
		dev.minesino.client.ClientNet.send("admin", "overview", new JsonObject(), r -> {
			if (r.ok()) overview = r.data();
		});
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		Ui.card(g, x, y, w, 62, 7, Theme.CARD, Theme.alpha(Theme.RED, 0.6f));
		if (overview != null) {
			Ui.text(g, Ui.tr("minesino.admin.accounts", Json.getInt(overview, "accounts", 0)), x + 10, y + 8, Theme.TEXT);
			Ui.text(g, Ui.tr("minesino.admin.supply", ClientState.money(Json.getLong(overview, "supply", 0))), x + 10, y + 20, Theme.GOLD);
			Ui.text(g, Ui.tr("minesino.admin.prices", Json.getInt(overview, "pricedItems", 0), Json.getInt(overview, "pricedMobs", 0)), x + 10, y + 32, Theme.TEXT_DIM);
			Ui.text(g, Ui.tr("minesino.admin.warnings", Json.getInt(overview, "priceWarnings", 0)), x + 10, y + 44, Theme.MUTED);
		}
		Ui.text(g, Ui.tr("minesino.admin.commands"), x + 160, y + 75, Theme.MUTED);
		float my2 = y + 96;
		for (String m : messages) {
			if (my2 > y + h - 10) break;
			Ui.text(g, Ui.fit(m, w), x, my2, Theme.GOLD);
			my2 += 10;
		}
	}
}
