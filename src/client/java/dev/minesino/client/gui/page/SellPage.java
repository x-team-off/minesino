package dev.minesino.client.gui.page;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Modal;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.ScrollState;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * Selling items from the inventory. The server lists what can be sold and for how much; before
 * confirming, it previews exactly which items are taken and what is credited.
 */
public class SellPage extends Page {
	private static final class Row {
		int slot, count, chosen;
		String item, reason;
		boolean sellable;
		long unit, stack;
		ItemStack display;
	}

	private final List<Row> rows = new ArrayList<>();
	private final ScrollState scroll = new ScrollState();
	private boolean loading = true;
	private final float rowH = 20;
	private float listY, listH;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.sell");
	}

	@Override
	protected void layout() {
		listY = y + 14;
		listH = h - 14 - 28;
		float bw2 = (w - 6) / 2;
		add(new Button(() -> Ui.tr("minesino.sell.sell_selected", ClientState.money(selectedTotal())), Button.Style.GOLD, this::sellSelected)
			.when(() -> !busy && selectedTotal() > 0)).bounds(x + w - bw2, y + h - 22, bw2, 20);
		add(Button.of(Ui.tr("minesino.sell.sell_all"), Button.Style.GHOST, this::sellAll)
			.when(() -> !busy && rows.stream().anyMatch(r -> r.sellable))).bounds(x, y + h - 22, bw2 * 0.6f, 20);
		add(Button.of(Ui.tr("minesino.sell.select_all"), Button.Style.GHOST, () -> rows.forEach(r -> r.chosen = r.sellable ? r.count : 0))
			.when(() -> !busy)).bounds(x + bw2 * 0.6f + 6, y + h - 22, bw2 * 0.4f - 6, 20);
	}

	@Override
	public void onShow() {
		refresh();
	}

	private void refresh() {
		ClientNet.send("sell", "inventory", new JsonObject(), r -> {
			loading = false;
			if (r.ok()) load(r.data().getAsJsonArray("slots"));
			else showError(r.error());
		});
	}

	private void load(JsonArray slots) {
		rows.clear();
		for (JsonElement e : slots) {
			JsonObject o = e.getAsJsonObject();
			Row r = new Row();
			r.slot = Json.getInt(o, "slot", 0);
			r.item = Json.getString(o, "item", "");
			r.count = Json.getInt(o, "count", 0);
			r.sellable = Json.getBool(o, "sellable", false);
			r.reason = Json.getString(o, "reason", "");
			r.unit = Json.getLong(o, "unit", 0);
			r.stack = Json.getLong(o, "stack", 0);
			var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(r.item));
			r.display = item == null ? ItemStack.EMPTY : new ItemStack(item, r.count);
			rows.add(r);
		}
		rows.sort((a, b) -> Boolean.compare(b.sellable, a.sellable));
	}

	/** Estimate for the button label; the exact sum comes from the server preview. */
	private long selectedTotal() {
		long t = 0;
		for (Row r : rows) {
			if (r.chosen <= 0) continue;
			t += r.chosen == r.count ? r.stack : r.unit * r.chosen;
		}
		return t;
	}

	private void sellSelected() {
		JsonArray lines = new JsonArray();
		for (Row r : rows) {
			if (r.chosen <= 0 || !r.sellable) continue;
			JsonObject l = new JsonObject();
			l.addProperty("slot", r.slot);
			l.addProperty("item", r.item);
			l.addProperty("count", r.chosen);
			lines.add(l);
		}
		JsonObject p = new JsonObject();
		p.add("lines", lines);
		preview(p);
	}

	private void sellAll() {
		JsonObject p = new JsonObject();
		p.addProperty("allOf", "*");
		preview(p);
	}

	private void preview(JsonObject params) {
		request("sell", "preview", params, r -> {
			if (!r.ok()) return;
			long total = Json.getLong(r.data(), "total", 0);
			List<Modal.Line> lines = new ArrayList<>();
			JsonArray planned = r.data().getAsJsonArray("lines");
			for (int i = 0; i < planned.size() && i < 6; i++) {
				JsonObject l = planned.get(i).getAsJsonObject();
				var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(Json.getString(l, "item", "")));
				ItemStack st = item == null ? ItemStack.EMPTY : new ItemStack(item);
				lines.add(new Modal.Line(st.getHoverName().getString() + " ×" + Json.getInt(l, "count", 0),
					ClientState.money(Json.getLong(l, "value", 0)), Theme.TEXT_DIM, st));
			}
			if (planned.size() > 6) lines.add(Modal.Line.of(Ui.tr("minesino.sell.more", planned.size() - 6), ""));
			lines.add(new Modal.Line(Ui.tr("minesino.sell.you_get"), ClientState.moneySigned(total), Theme.GREEN, ItemStack.EMPTY));
			JsonObject exec = params.deepCopy();
			exec.addProperty("expected", total);
			screen.openModal(new Modal(Ui.tr("minesino.sell.confirm_title"), lines, Ui.tr("minesino.sell.confirm"), Button.Style.GOLD,
				() -> request("sell", "sell", exec, res -> {
					if (res.ok()) {
						ClientState.toast(Ui.tr("minesino.sell.sold"), ClientState.moneySigned(total), Theme.GREEN);
						Ui.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.1f, 0.6f);
						if (res.data().has("inventory")) load(res.data().getAsJsonArray("inventory"));
					} else {
						refresh();
					}
				}), null));
		});
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		scroll.view = listH;
		scroll.content = rows.size() * rowH;
		scroll.update(dt);
		super.render(g, mx, my, dt);
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float c1 = x + 26, c2 = x + w * 0.46f, c3 = x + w * 0.62f, c4 = x + w * 0.80f;
		Ui.text(g, Ui.tr("minesino.sell.item"), c1, y + 2, Theme.MUTED);
		Ui.text(g, Ui.tr("minesino.sell.unit"), c2, y + 2, Theme.MUTED);
		Ui.text(g, Ui.tr("minesino.sell.amount"), c3, y + 2, Theme.MUTED);
		Ui.right(g, Ui.tr("minesino.sell.total"), x + w - 6, y + 2, Theme.MUTED);
		Ui.panel(g, x, listY, w, listH, 6, 0xFF120F1A, Theme.BORDER);
		if (loading) Ui.center(g, Ui.tr("minesino.hud.loading"), x + w / 2, listY + listH / 2, Theme.MUTED);
		else if (rows.isEmpty()) Ui.center(g, Ui.tr("minesino.sell.empty"), x + w / 2, listY + listH / 2, Theme.MUTED);
		g.enableScissor((int) x, (int) listY + 1, (int) (x + w), (int) (listY + listH) - 1);
		for (int i = 0; i < rows.size(); i++) {
			Row r = rows.get(i);
			float ry = listY + 2 + i * rowH - scroll.offset;
			if (ry + rowH < listY || ry > listY + listH) continue;
			boolean hov = Ui.inside(mx, my, x, ry, w, rowH) && my > listY && my < listY + listH;
			int bg = r.chosen > 0 ? 0xFF1E2A1F : hov ? Theme.CARD_HI : i % 2 == 0 ? 0xFF17131F : 0xFF1A1623;
			Ui.round(g, x + 2, ry, w - 4, rowH - 2, 4, bg);
			if (r.chosen > 0) Ui.round(g, x + 2, ry + 3, 2, rowH - 8, 1, Theme.GREEN);
			g.item(r.display, (int) x + 6, (int) ry + 1);
			int textCol = r.sellable ? Theme.TEXT : Theme.MUTED;
			Ui.text(g, Ui.fit(r.display.getHoverName().getString(), c2 - c1 - 6), c1, ry + 1.5f, textCol);
			Ui.text(g, "×" + r.count, c1, ry + 10, Theme.MUTED);
			if (r.sellable) {
				Ui.text(g, ClientState.money(r.unit), c2, ry + 6, Theme.TEXT_DIM);
				// quantity stepper
				float sx = c3;
				stepper(g, mx, my, sx, ry + 3, "−", r.chosen > 0);
				Ui.center(g, Integer.toString(r.chosen), sx + 26, ry + 6, r.chosen > 0 ? Theme.TEXT : Theme.MUTED);
				stepper(g, mx, my, sx + 40, ry + 3, "+", r.chosen < r.count);
				stepper(g, mx, my, sx + 54, ry + 3, "∞", r.chosen < r.count);
				long value = r.chosen == r.count ? r.stack : r.unit * r.chosen;
				Ui.right(g, r.chosen > 0 ? ClientState.money(value) : ClientState.money(r.stack), x + w - 8, ry + 6,
					r.chosen > 0 ? Theme.GOLD : Theme.TEXT_DIM);
			} else {
				Ui.text(g, Ui.fit(Ui.tr(r.reason.isEmpty() ? "minesino.sell.no_price" : r.reason), x + w - c2 - 8), c2, ry + 6, Theme.alpha(Theme.RED, 0.8f));
			}
		}
		g.disableScissor();
		scroll.bar(g, x + w - 4, listY);
	}

	private void stepper(GuiGraphicsExtractor g, int mx, int my, float sx, float sy, String label, boolean enabled) {
		boolean hov = enabled && Ui.inside(mx, my, sx, sy, 12, 12);
		Ui.round(g, sx, sy, 12, 12, 3, !enabled ? 0xFF1A1622 : hov ? Theme.PURPLE : Theme.CARD_HI);
		Ui.center(g, label, sx + 6.5f, sy + 2, enabled ? Theme.TEXT : Theme.MUTED);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (busy || !Ui.inside(mx, my, x, listY, w, listH)) return false;
		int i = (int) ((my - listY - 2 + scroll.offset) / rowH);
		if (i < 0 || i >= rows.size()) return false;
		Row r = rows.get(i);
		if (!r.sellable) return true;
		float c3 = x + w * 0.62f;
		float ry = listY + 2 + i * rowH - scroll.offset;
		boolean shift = net.minecraft.client.Minecraft.getInstance().hasShiftDown();
		int step = shift ? 16 : 1;
		if (Ui.inside(mx, my, c3, ry + 3, 12, 12)) r.chosen = Math.max(0, r.chosen - step);
		else if (Ui.inside(mx, my, c3 + 40, ry + 3, 12, 12)) r.chosen = Math.min(r.count, r.chosen + step);
		else if (Ui.inside(mx, my, c3 + 54, ry + 3, 12, 12)) r.chosen = r.count;
		else r.chosen = r.chosen > 0 ? 0 : r.count; // click on the row toggles the whole stack
		Ui.clickSound();
		return true;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scroll.scroll(amount, 20);
		return true;
	}
}
