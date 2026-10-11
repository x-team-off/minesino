package dev.minesino.selling;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.economy.Json;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import dev.minesino.server.MineSinoServer;
import dev.minesino.tablet.TabletItems;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Selling items from the main inventory (slots 0..35). The request is re-validated against the
 * live server inventory, then items are removed and money credited in the same tick; if crediting
 * fails, the removed stacks are put back exactly as they were.
 */
public final class ItemSaleService {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Selling");
	public static final int MAIN_SLOTS = 36;
	private final MineSinoServer ms;

	public ItemSaleService(MineSinoServer ms) {
		this.ms = ms;
	}

	public static String idOf(ItemStack s) {
		return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
	}

	public static boolean isModified(ItemStack s) {
		return !s.getComponentsPatch().isEmpty();
	}

	public Map<Integer, SalePlanner.SlotContent> snapshot(ServerPlayer player) {
		Map<Integer, SalePlanner.SlotContent> m = new LinkedHashMap<>();
		Inventory inv = player.getInventory();
		for (int i = 0; i < MAIN_SLOTS; i++) {
			ItemStack s = inv.getItem(i);
			if (!s.isEmpty()) m.put(i, new SalePlanner.SlotContent(i, idOf(s), s.getCount(), isModified(s)));
		}
		return m;
	}

	/** Inventory listing with server prices for the selling screen. */
	public JsonObject inventoryView(ServerPlayer player) {
		JsonObject o = new JsonObject();
		JsonArray slots = new JsonArray();
		var cfg = ms.config();
		for (SalePlanner.SlotContent c : snapshot(player).values()) {
			JsonObject e = new JsonObject();
			e.addProperty("slot", c.slot());
			e.addProperty("item", c.itemId());
			e.addProperty("count", c.count());
			boolean priced = ms.prices().sellUnitPrice(c.itemId()) != null && !ms.prices().isBanned(c.itemId());
			boolean allowed = priced && (!c.modified() || cfg.selling.allowModifiedItems);
			e.addProperty("sellable", allowed);
			if (!priced) e.addProperty("reason", ms.prices().isBanned(c.itemId()) ? "minesino.error.not_sellable" : "minesino.sell.no_price");
			else if (!allowed) e.addProperty("reason", "minesino.error.modified_item");
			e.addProperty("unit", ms.prices().sellValue(c.itemId(), 1, cfg.selling.sellMultiplier));
			e.addProperty("stack", ms.prices().sellValue(c.itemId(), c.count(), cfg.selling.sellMultiplier));
			e.addProperty("category", ms.prices().category(c.itemId()).name());
			slots.add(e);
		}
		o.add("slots", slots);
		return o;
	}

	public static List<SalePlanner.Line> parseLines(JsonObject params) {
		List<SalePlanner.Line> lines = new ArrayList<>();
		if (!params.has("lines") || !params.get("lines").isJsonArray()) return lines;
		for (JsonElement e : params.getAsJsonArray("lines")) {
			if (!e.isJsonObject()) continue;
			JsonObject l = e.getAsJsonObject();
			lines.add(new SalePlanner.Line(Json.getInt(l, "slot", -1), Json.getString(l, "item", ""), Json.getInt(l, "count", 0)));
		}
		return lines;
	}

	public SalePlanner.Plan preview(ServerPlayer player, List<SalePlanner.Line> lines) {
		return SalePlanner.plan(lines, snapshot(player), ms.prices(), ms.config());
	}

	public SalePlanner.Plan previewAll(ServerPlayer player, String itemId) {
		Map<Integer, SalePlanner.SlotContent> snap = snapshot(player);
		List<SalePlanner.Line> lines = SalePlanner.allOf(itemId, snap, ms.prices(), ms.config());
		if (lines.isEmpty()) return new SalePlanner.Plan(List.of(), 0, 0, "minesino.error.nothing_to_sell");
		return SalePlanner.plan(lines, snap, ms.prices(), ms.config());
	}

	/** Executes a plan atomically. Returns null on success or an error key. */
	public String execute(ServerPlayer player, SalePlanner.Plan plan, String opId) {
		if (!plan.ok()) return plan.error();
		Inventory inv = player.getInventory();
		// Re-check against the live inventory right before removal (same tick as the plan).
		Map<Integer, ItemStack> backup = new LinkedHashMap<>();
		for (SalePlanner.PlannedLine l : plan.lines()) {
			ItemStack s = inv.getItem(l.slot());
			if (s.isEmpty() || !idOf(s).equals(l.itemId())) return "minesino.error.inventory_changed";
			backup.putIfAbsent(l.slot(), s.copy());
		}
		Map<Integer, Integer> need = new LinkedHashMap<>();
		for (SalePlanner.PlannedLine l : plan.lines()) need.merge(l.slot(), l.count(), Integer::sum);
		for (Map.Entry<Integer, Integer> e : need.entrySet()) {
			if (inv.getItem(e.getKey()).getCount() < e.getValue()) return "minesino.error.inventory_changed";
		}
		for (Map.Entry<Integer, Integer> e : need.entrySet()) inv.getItem(e.getKey()).shrink(e.getValue());
		String detail = describe(plan);
		TxResult r = ms.economy.deposit(player.getUUID(), plan.total(), TxType.ITEM_SALE, detail, opId);
		if (!r.ok()) {
			for (Map.Entry<Integer, ItemStack> e : backup.entrySet()) inv.setItem(e.getKey(), e.getValue());
			inv.setChanged();
			LOG.warn("Sale by {} rolled back: {}", player.getGameProfile().name(), r.status());
			return r.status() == TxResult.Status.LIMIT_EXCEEDED ? "minesino.error.limit_exceeded" : "minesino.error.transaction_failed";
		}
		inv.setChanged();
		player.containerMenu.broadcastChanges();
		ms.economy.updateStats(player.getUUID(), s -> {
			s.itemsSold += plan.itemCount();
			s.itemSaleIncome += plan.total();
		});
		return null;
	}

	private static String describe(SalePlanner.Plan plan) {
		Map<String, Integer> counts = new LinkedHashMap<>();
		for (SalePlanner.PlannedLine l : plan.lines()) counts.merge(l.itemId(), l.count(), Integer::sum);
		StringBuilder sb = new StringBuilder();
		for (Map.Entry<String, Integer> e : counts.entrySet()) {
			if (sb.length() > 120) {
				sb.append(" ...");
				break;
			}
			if (!sb.isEmpty()) sb.append(", ");
			sb.append(e.getKey()).append(" x").append(e.getValue());
		}
		return sb.toString();
	}

	public static JsonObject planJson(SalePlanner.Plan plan) {
		JsonObject o = new JsonObject();
		o.addProperty("total", plan.total());
		o.addProperty("items", plan.itemCount());
		JsonArray lines = new JsonArray();
		for (SalePlanner.PlannedLine l : plan.lines()) {
			JsonObject e = new JsonObject();
			e.addProperty("slot", l.slot());
			e.addProperty("item", l.itemId());
			e.addProperty("count", l.count());
			e.addProperty("value", l.value());
			lines.add(e);
		}
		o.add("lines", lines);
		return o;
	}

	/** Deletes forged tablet items: the real tablet never exists as an item stack. */
	public void purgeForgedTablets(ServerPlayer player) {
		Inventory inv = player.getInventory();
		boolean removed = false;
		for (int i = 0; i < inv.getContainerSize(); i++) {
			if (TabletItems.isTablet(inv.getItem(i))) {
				inv.setItem(i, ItemStack.EMPTY);
				removed = true;
			}
		}
		if (TabletItems.isTablet(player.containerMenu.getCarried())) {
			player.containerMenu.setCarried(ItemStack.EMPTY);
			removed = true;
		}
		if (removed) {
			inv.setChanged();
			ms.journal.security(player.getGameProfile().name(), "forged tablet item removed from inventory");
		}
	}

	/** Item stakes for the Upgrade game, valued at the sale price. */
	public CasinoService.ItemStakes itemStakes() {
		return new CasinoService.ItemStakes() {
			@Override
			public long value(UUID playerId, int slot, String itemId, int count) throws GameException {
				ServerPlayer p = ms.server.getPlayerList().getPlayer(playerId);
				if (p == null) throw new GameException("minesino.error.items_unavailable");
				SalePlanner.Plan plan = preview(p, List.of(new SalePlanner.Line(slot, itemId, count)));
				if (!plan.ok()) throw new GameException(plan.error());
				return plan.total();
			}

			@Override
			public void take(UUID playerId, int slot, String itemId, int count) throws GameException {
				ServerPlayer p = ms.server.getPlayerList().getPlayer(playerId);
				if (p == null) throw new GameException("minesino.error.items_unavailable");
				ItemStack s = p.getInventory().getItem(slot);
				if (slot < 0 || slot >= MAIN_SLOTS || s.isEmpty() || !idOf(s).equals(itemId) || s.getCount() < count) {
					throw new GameException("minesino.error.inventory_changed");
				}
				s.shrink(count);
				p.getInventory().setChanged();
				p.containerMenu.broadcastChanges();
			}
		};
	}
}
