package dev.minesino.client.gui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Transaction history with type filters and pagination (data from the server ledger). */
public class HistoryPage extends Page {
	private static final String[] GROUPS = {"ALL", "CASINO", "SALES", "PURCHASES", "ADMIN"};
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());
	private String group = "ALL";
	private int page, pages = 1, total;
	private JsonArray entries = new JsonArray();
	private boolean loading = true;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.history");
	}

	@Override
	protected void layout() {
		float cw = Math.min(70, (w - 16) / 5);
		for (int i = 0; i < GROUPS.length; i++) {
			String gr = GROUPS[i];
			add(new Button(() -> Ui.tr("minesino.history.group." + gr.toLowerCase(Locale.ROOT)), Button.Style.CHIP, () -> {
				group = gr;
				page = 0;
				load();
			}).select(() -> group.equals(gr))).bounds(x + i * (cw + 4), y, cw, 14);
		}
		add(Button.of("‹", Button.Style.GHOST, () -> {
			page--;
			load();
		}).when(() -> page > 0 && !busy)).bounds(x + w - 74, y + h - 16, 18, 15);
		add(Button.of("›", Button.Style.GHOST, () -> {
			page++;
			load();
		}).when(() -> page < pages - 1 && !busy)).bounds(x + w - 18, y + h - 16, 18, 15);
	}

	@Override
	public void onShow() {
		load();
	}

	private void load() {
		JsonObject p = new JsonObject();
		p.addProperty("group", group);
		p.addProperty("page", page);
		request("data", "history", p, r -> {
			loading = false;
			if (!r.ok()) return;
			entries = r.data().getAsJsonArray("entries");
			page = Json.getInt(r.data(), "page", 0);
			pages = Json.getInt(r.data(), "pages", 1);
			total = Json.getInt(r.data(), "total", 0);
		});
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float ly = y + 20, lh = h - 40;
		Ui.panel(g, x, ly, w, lh, 6, 0xFF1A0F10, Theme.BORDER);
		float c1 = x + 6, c2 = x + 6 + Ui.width("00.00 00:00") + 8, c3 = x + w * 0.44f;
		Ui.text(g, Ui.tr("minesino.history.time"), c1, ly + 4, Theme.MUTED);
		Ui.text(g, Ui.tr("minesino.history.type"), c2, ly + 4, Theme.MUTED);
		Ui.text(g, Ui.tr("minesino.history.detail"), c3, ly + 4, Theme.MUTED);
		Ui.right(g, Ui.tr("minesino.history.change"), x + w * 0.84f, ly + 4, Theme.MUTED);
		Ui.right(g, Ui.tr("minesino.history.balance"), x + w - 6, ly + 4, Theme.MUTED);
		float ry = ly + 16;
		float rowH = Math.min(20, (lh - 18) / 10f);
		if (loading) Ui.center(g, Ui.tr("minesino.hud.loading"), x + w / 2, ly + lh / 2, Theme.MUTED);
		else if (entries.isEmpty()) Ui.center(g, Ui.tr("minesino.history.empty"), x + w / 2, ly + lh / 2, Theme.MUTED);
		int i = 0;
		for (JsonElement e : entries) {
			JsonObject o = e.getAsJsonObject();
			if (ry + rowH > ly + lh) break;
			Ui.round(g, x + 2, ry, w - 4, rowH - 2, 3, i % 2 == 0 ? 0xFF120A0C : 0xFF251719);
			float ty = ry + (rowH - 2 - 8) / 2;
			long delta = Json.getLong(o, "delta", 0);
			String type = Json.getString(o, "type", "").toLowerCase(Locale.ROOT);
			String outcome = Json.getString(o, "outcome", "");
			Ui.text(g, TIME.format(Instant.ofEpochMilli(Json.getLong(o, "time", 0))), c1, ty, Theme.TEXT_DIM);
			String typeKey = delta == 0 && type.equals("payout") ? "minesino.tx.round_result" : "minesino.tx." + type;
			String typeLabel = Ui.tr(typeKey) + (outcome.isEmpty() ? "" : " · " + Ui.tr("minesino.outcome." + outcome));
			Ui.text(g, Ui.fit(typeLabel, c3 - c2 - 4), c2, ty, Theme.TEXT);
			Ui.text(g, Ui.fit(prettyDetail(Json.getString(o, "detail", "")), x + w * 0.84f - c3 - Ui.width("+00 000 MS") - 4), c3, ty, Theme.MUTED);
			int col = delta > 0 ? Theme.GREEN : delta < 0 ? Theme.RED : Theme.MUTED;
			Ui.right(g, delta == 0 ? "—" : ClientState.moneySigned(delta), x + w * 0.84f, ty, col);
			Ui.right(g, ClientState.money(Json.getLong(o, "balance", 0)), x + w - 6, ty, Theme.TEXT_DIM);
			ry += rowH;
			i++;
		}
		Ui.text(g, Ui.tr("minesino.history.page", page + 1, pages, total), x, y + h - 12, Theme.MUTED);
	}

	/** "roulette#r1x2" -> localized game name; item ids -> item names. */
	static String prettyDetail(String d) {
		int hash = d.indexOf('#');
		if (hash > 0) {
			String game = d.substring(0, hash);
			String key = "minesino.game." + game;
			String tr = Ui.tr(key);
			return tr.equals(key) ? d : tr;
		}
		if (d.startsWith("minecraft:")) {
			int sp = d.indexOf(' ');
			String id = sp > 0 ? d.substring(0, sp) : d;
			var item = net.minecraft.core.registries.BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.tryParse(id));
			if (item != null) return new net.minecraft.world.item.ItemStack(item).getHoverName().getString() + (sp > 0 ? d.substring(sp) : "");
		}
		return d;
	}
}
