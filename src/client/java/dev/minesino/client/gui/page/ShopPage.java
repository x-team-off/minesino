package dev.minesino.client.gui.page;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Modal;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.ScrollState;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.client.gui.widget.TextField;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * "Withdraw funds": spend balance on items, including allowed spawn eggs. Prices shown are the
 * server's; the final total is quoted by the server and the purchase is refused if it changed.
 */
public class ShopPage extends Page {
	private record Entry(String id, String cat, long unit, ItemStack stack, String name) {}

	private static final int[] QUICK = {1, 4, 16, 32, 64};
	private final List<Entry> all = new ArrayList<>();
	private final List<Entry> filtered = new ArrayList<>();
	private final List<String> categories = new ArrayList<>();
	private String category = "ALL";
	private Entry selected;
	private int count = 1;
	private int maxCount = 1728;
	private long quote = -1;
	private long quoteFor = -1;
	private long quoteRequested;
	private boolean loading = true;
	private TextField search, countField;
	private final ScrollState scroll = new ScrollState();
	private float gridX, gridY, gridW, gridH, detailX, detailW, catW;
	private final float cell = 20;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.shop");
	}

	@Override
	protected void layout() {
		catW = Math.min(84, w * 0.2f);
		detailW = Math.min(140, w * 0.32f);
		detailX = x + w - detailW;
		gridX = x + catW + 6;
		gridY = y + 20;
		gridW = detailX - 6 - gridX;
		gridH = h - 20;
		search = add(new TextField());
		search.placeholder = Ui.tr("minesino.shop.search");
		search.maxLength = 32;
		search.onChange = s -> applyFilter();
		search.bounds(gridX, y, gridW, 15);
		float qy = y + 92;
		float qw = (detailW - 16) / 5;
		for (int i = 0; i < QUICK.length; i++) {
			int q = QUICK[i];
			add(new Button(() -> Integer.toString(q), Button.Style.CHIP, () -> setCount(q)).select(() -> count == q)
				.when(() -> selected != null)).bounds(detailX + i * (qw + 4), qy, qw, 14);
		}
		add(Button.of("−", Button.Style.GHOST, () -> setCount(count - 1)).when(() -> selected != null && count > 1)).bounds(detailX, qy + 18, 16, 15);
		countField = add(new TextField());
		countField.allowed = Character::isDigit;
		countField.maxLength = 5;
		countField.setText("1");
		countField.onChange = s -> {
			try {
				count = Math.clamp(Integer.parseInt(s), 1, maxCount);
			} catch (NumberFormatException e) {
				count = 1;
			}
			quoteFor = -1;
		};
		countField.bounds(detailX + 20, qy + 18, detailW - 40, 15);
		add(Button.of("+", Button.Style.GHOST, () -> setCount(count + 1)).when(() -> selected != null && count < maxCount))
			.bounds(detailX + detailW - 16, qy + 18, 16, 15);
		add(new Button(() -> busy ? "…" : Ui.tr("minesino.shop.buy"), Button.Style.GOLD, this::confirmBuy)
			.when(() -> selected != null && !busy && quote > 0 && quoteFor == key() && quote <= ClientState.balance))
			.bounds(detailX, y + h - 20, detailW, 20);
	}

	private long key() {
		return selected == null ? -1 : ((long) selected.id().hashCode() << 20) ^ count;
	}

	private void setCount(int c) {
		count = Math.clamp(c, 1, maxCount);
		countField.setText(Integer.toString(count));
		quoteFor = -1;
	}

	@Override
	public void onShow() {
		ClientNet.send("buy", "catalog", new JsonObject(), r -> {
			loading = false;
			if (!r.ok()) {
				showError(r.error());
				return;
			}
			all.clear();
			Set<String> cats = new LinkedHashSet<>();
			maxCount = Json.getInt(r.data(), "max", 1728);
			for (JsonElement e : r.data().getAsJsonArray("items")) {
				JsonObject o = e.getAsJsonObject();
				String id = Json.getString(o, "id", "");
				var item = BuiltInRegistries.ITEM.getValue(Identifier.tryParse(id));
				if (item == null) continue;
				ItemStack st = new ItemStack(item);
				String cat = Json.getString(o, "cat", "SPECIAL");
				all.add(new Entry(id, cat, Json.getLong(o, "unit", 0), st, st.getHoverName().getString()));
				cats.add(cat);
			}
			categories.clear();
			categories.add("ALL");
			categories.addAll(cats);
			applyFilter();
		});
	}

	/** Items shown with the current category and search (for tests). */
	public int visibleCount() {
		return filtered.size();
	}

	public int totalCount() {
		return all.size();
	}

	/** Lower case, "ё" as "е", "_" and ":" as spaces, so "блок алмаза", "diamond block" and "diamond_block" all match. */
	private static String norm(String s) {
		return s.toLowerCase(Locale.ROOT).replace('ё', 'е').replace('_', ' ').replace(':', ' ');
	}

	private void applyFilter() {
		filtered.clear();
		String[] words = norm(search == null ? "" : search.text()).trim().split("\\s+");
		boolean searching = words.length > 0 && !words[0].isEmpty();
		for (Entry e : all) {
			// A search looks through every category, otherwise "алмаз" finds nothing while "Food" is open.
			if (!searching && !category.equals("ALL") && !e.cat().equals(category)) continue;
			if (searching) {
				String hay = norm(e.name()) + " " + norm(e.id());
				boolean match = true;
				for (String wd : words) if (!hay.contains(wd)) { match = false; break; }
				if (!match) continue;
			}
			filtered.add(e);
		}
		scroll.reset();
	}

	@Override
	public void tick() {
		super.tick();
		// Ask the server for the exact total (rounding, overrides) when selection or quantity changes.
		if (selected != null && quoteFor != key() && Anim.now() - quoteRequested > 200 && !busy) {
			long k = key();
			quoteRequested = Anim.now();
			JsonObject p = new JsonObject();
			p.addProperty("item", selected.id());
			p.addProperty("count", count);
			ClientNet.send("buy", "quote", p, r -> {
				if (key() != k) return;
				quoteFor = k;
				quote = r.ok() ? Json.getLong(r.data(), "total", -1) : -1;
			});
		}
	}

	private void confirmBuy() {
		if (selected == null || quote <= 0) return;
		Entry e = selected;
		int c = count;
		long total = quote;
		List<Modal.Line> lines = List.of(
			new Modal.Line(e.name() + " ×" + c, "", Theme.TEXT, e.stack()),
			Modal.Line.of(Ui.tr("minesino.shop.total"), ClientState.money(total)),
			Modal.Line.of(Ui.tr("minesino.shop.balance_after"), ClientState.money(ClientState.balance - total)));
		screen.openModal(new Modal(Ui.tr("minesino.shop.confirm_title"), lines, Ui.tr("minesino.shop.buy"), Button.Style.GOLD, () -> {
			JsonObject p = new JsonObject();
			p.addProperty("item", e.id());
			p.addProperty("count", c);
			p.addProperty("expected", total);
			request("buy", "buy", p, r -> {
				if (r.ok()) {
					ClientState.toast(Ui.tr("minesino.shop.bought"), e.name() + " ×" + c + "  " + ClientState.moneySigned(-total), Theme.GOLD);
					Ui.sound(SoundEvents.ITEM_PICKUP, 1.0f, 0.8f);
				} else if ("minesino.error.inventory_full".equals(r.error())) {
					showError("minesino.error.inventory_full");
				}
				quoteFor = -1;
			});
		}, null));
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		scroll.view = gridH;
		int perRow = Math.max(1, (int) ((gridW - 4) / cell));
		scroll.content = ((filtered.size() + perRow - 1) / perRow) * cell + 4;
		scroll.update(dt);
		super.render(g, mx, my, dt);
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		// categories
		float cy = y;
		for (String c : categories) {
			boolean sel = c.equals(category);
			boolean hov = Ui.inside(mx, my, x, cy, catW, 13);
			if (sel || hov) Ui.round(g, x, cy, catW, 13, 4, sel ? Theme.alpha(Theme.ACCENT, 0.35f) : Theme.CARD);
			if (sel) Ui.round(g, x, cy + 2, 2, 9, 1, Theme.GOLD);
			Ui.text(g, Ui.fit(catLabel(c), catW - 8), x + 5, cy + 2.5f, sel ? Theme.TEXT : Theme.TEXT_DIM);
			cy += 14;
			if (cy > y + h - 12) break;
		}
		// grid
		Ui.panel(g, gridX, gridY, gridW, gridH, 6, 0xFF1A0F10, Theme.BORDER);
		if (loading) Ui.center(g, Ui.tr("minesino.hud.loading"), gridX + gridW / 2, gridY + gridH / 2, Theme.MUTED);
		else if (filtered.isEmpty()) Ui.center(g, Ui.tr("minesino.shop.empty"), gridX + gridW / 2, gridY + gridH / 2, Theme.MUTED);
		int perRow = Math.max(1, (int) ((gridW - 4) / cell));
		float pad = (gridW - perRow * cell) / 2;
		g.enableScissor((int) gridX, (int) gridY + 1, (int) (gridX + gridW), (int) (gridY + gridH) - 1);
		Entry hovered = null;
		for (int i = 0; i < filtered.size(); i++) {
			float ix = gridX + pad + (i % perRow) * cell, iy = gridY + 2 + (i / perRow) * cell - scroll.offset;
			if (iy + cell < gridY || iy > gridY + gridH) continue;
			Entry e = filtered.get(i);
			boolean sel = e == selected, hov = Ui.inside(mx, my, ix, iy, cell, cell) && my > gridY && my < gridY + gridH;
			if (sel || hov) Ui.round(g, ix + 1, iy + 1, cell - 2, cell - 2, 3, sel ? Theme.alpha(Theme.GOLD, 0.35f) : Theme.CARD_HI);
			g.item(e.stack(), (int) (ix + 2), (int) (iy + 2));
			if (hov) hovered = e;
		}
		g.disableScissor();
		scroll.bar(g, gridX + gridW - 4, gridY);
		if (hovered != null) {
			g.setTooltipForNextFrame(Ui.font(), net.minecraft.network.chat.Component.literal(hovered.name() + " · " + ClientState.money(hovered.unit())), mx, my);
		}
		renderDetail(g);
	}

	private String catLabel(String c) {
		return c.equals("ALL") ? Ui.tr("minesino.shop.all") : c.equals("SPAWN_EGGS") ? Ui.tr("minesino.shop.eggs")
			: Ui.tr("minesino.category." + c.toLowerCase(Locale.ROOT));
	}

	private void renderDetail(GuiGraphicsExtractor g) {
		Ui.panel(g, detailX, y, detailW, h - 24, 7, Theme.CARD, Theme.BORDER);
		if (selected == null) {
			Ui.center(g, Ui.tr("minesino.shop.pick"), detailX + detailW / 2, y + 40, Theme.MUTED);
			return;
		}
		Ui.glow(g, detailX + detailW / 2 - 14, y + 8, 28, 28, 8, Theme.alpha(Theme.GOLD, 0.25f));
		Ui.item(g, selected.stack(), detailX + detailW / 2 - 14, y + 8, 28);
		Ui.center(g, Ui.fit(selected.name(), detailW - 10), detailX + detailW / 2, y + 42, Theme.TEXT);
		Ui.center(g, Ui.tr("minesino.shop.unit", ClientState.money(selected.unit())), detailX + detailW / 2, y + 54, Theme.TEXT_DIM);
		Ui.text(g, Ui.tr("minesino.shop.quantity"), detailX + 6, y + 80, Theme.MUTED);
		float ry = y + 130;
		boolean fresh = quoteFor == key();
		String total = fresh && quote > 0 ? ClientState.money(quote) : fresh ? Ui.tr("minesino.shop.unavailable") : "…";
		row(g, Ui.tr("minesino.shop.total"), total, Theme.GOLD, ry);
		row(g, Ui.tr("minesino.shop.balance"), ClientState.money(ClientState.balance), Theme.TEXT_DIM, ry + 11);
		if (fresh && quote > 0) {
			long after = ClientState.balance - quote;
			row(g, Ui.tr("minesino.shop.balance_after"), ClientState.money(after), after >= 0 ? Theme.GREEN : Theme.RED, ry + 22);
		}
	}

	private void row(GuiGraphicsExtractor g, String l, String v, int color, float ry) {
		if (ry > y + h - 34) return;
		Ui.text(g, l, detailX + 6, ry, Theme.MUTED);
		Ui.right(g, Ui.fit(v, detailW * 0.6f), detailX + detailW - 6, ry, color);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		float cy = y;
		for (String c : categories) {
			if (Ui.inside(mx, my, x, cy, catW, 13)) {
				category = c;
				applyFilter();
				Ui.clickSound();
				return true;
			}
			cy += 14;
		}
		if (Ui.inside(mx, my, gridX, gridY, gridW, gridH)) {
			int perRow = Math.max(1, (int) ((gridW - 4) / cell));
			float pad = (gridW - perRow * cell) / 2;
			int col = (int) ((mx - gridX - pad) / cell), row = (int) ((my - gridY - 2 + scroll.offset) / cell);
			int i = row * perRow + col;
			if (col >= 0 && col < perRow && i >= 0 && i < filtered.size()) {
				selected = filtered.get(i);
				setCount(Math.min(count, selected.stack().getMaxStackSize() == 1 ? 1 : count));
				quoteFor = -1;
				Ui.clickSound();
				return true;
			}
		}
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (Ui.inside(mx, my, gridX, gridY, gridW, gridH)) {
			scroll.scroll(amount, 24);
			return true;
		}
		return super.mouseScrolled(mx, my, amount);
	}
}
