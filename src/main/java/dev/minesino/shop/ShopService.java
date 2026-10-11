package dev.minesino.shop;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import dev.minesino.pricing.PriceCategory;
import dev.minesino.server.MineSinoServer;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.SpawnEggItem;

/**
 * "Withdraw" shop: spends balance on items. The price is always computed on the server, the
 * balance and free inventory space are checked first, and money is charged only when the items
 * fit. Items are identified by registry id, never by display name.
 */
public final class ShopService {
	private final MineSinoServer ms;
	private volatile String catalogCache;

	public ShopService(MineSinoServer ms) {
		this.ms = ms;
	}

	public void invalidate() {
		catalogCache = null;
	}

	public static String spawnEggEntity(Item item) {
		if (!(item instanceof SpawnEggItem)) return null;
		EntityType<?> type = SpawnEggItem.getType(new ItemStack(item));
		return type == null ? null : BuiltInRegistries.ENTITY_TYPE.getKey(type).toString();
	}

	public ShopPolicy.Offer quote(String itemId, int count) {
		Identifier id = Identifier.tryParse(itemId);
		if (id == null || !BuiltInRegistries.ITEM.containsKey(id)) return new ShopPolicy.Offer(itemId, -1, -1, "minesino.error.item_forbidden");
		Item item = BuiltInRegistries.ITEM.getValue(id);
		return ShopPolicy.quote(itemId, count, spawnEggEntity(item), ms.prices(), ms.mobPrices(), ms.config());
	}

	/** Full catalogue (cached until prices or config reload). */
	public String catalog() {
		String c = catalogCache;
		if (c != null) return c;
		record Entry(String id, String cat, long unit, String entity) {}
		List<Entry> entries = new ArrayList<>();
		for (Item item : BuiltInRegistries.ITEM) {
			String id = BuiltInRegistries.ITEM.getKey(item).toString();
			String entity = spawnEggEntity(item);
			ShopPolicy.Offer o = ShopPolicy.quote(id, 1, entity, ms.prices(), ms.mobPrices(), ms.config());
			if (!o.ok()) continue;
			String cat = entity != null ? "SPAWN_EGGS" : ms.prices().category(id).name();
			entries.add(new Entry(id, cat, o.price(), entity));
		}
		entries.sort(Comparator.comparing((Entry e) -> catOrder(e.cat())).thenComparing(Entry::id));
		JsonObject root = new JsonObject();
		JsonArray arr = new JsonArray();
		for (Entry e : entries) {
			JsonObject o = new JsonObject();
			o.addProperty("id", e.id());
			o.addProperty("cat", e.cat());
			o.addProperty("unit", e.unit());
			if (e.entity() != null) o.addProperty("entity", e.entity());
			arr.add(o);
		}
		root.add("items", arr);
		root.addProperty("max", ms.config().shop.maxPurchaseCount);
		c = root.toString();
		catalogCache = c;
		return c;
	}

	private static int catOrder(String cat) {
		if (cat.equals("SPAWN_EGGS")) return 100;
		return PriceCategory.parse(cat, PriceCategory.SPECIAL).ordinal();
	}

	/** How many of {@code proto} fit into the main inventory right now. */
	public static int capacity(Inventory inv, ItemStack proto) {
		int max = proto.getMaxStackSize();
		int free = 0;
		for (int i = 0; i < 36; i++) {
			ItemStack s = inv.getItem(i);
			if (s.isEmpty()) free += max;
			else if (ItemStack.isSameItemSameComponents(s, proto)) free += Math.max(0, max - s.getCount());
		}
		return free;
	}

	/**
	 * Buys items. {@code expectedTotal} is what the client displayed; if prices changed in between
	 * the purchase is refused instead of charging a different amount.
	 * @return null on success, otherwise an error key
	 */
	public String buy(ServerPlayer player, String itemId, int count, long expectedTotal, String opId, JsonObject out) {
		ShopPolicy.Offer offer = quote(itemId, count);
		if (!offer.ok()) return offer.error();
		out.addProperty("total", offer.price());
		if (expectedTotal != offer.price()) return "minesino.error.price_changed";
		if (ms.economy.balance(player.getUUID()) < offer.price()) return "minesino.error.insufficient_funds";
		Item item = BuiltInRegistries.ITEM.getValue(Identifier.parse(itemId));
		ItemStack proto = new ItemStack(item);
		Inventory inv = player.getInventory();
		int cap = capacity(inv, proto);
		if (cap < count) {
			out.addProperty("fits", cap);
			return "minesino.error.inventory_full";
		}
		TxResult r = ms.economy.withdraw(player.getUUID(), offer.price(), TxType.PURCHASE, itemId + " x" + count, opId);
		if (!r.ok()) return r.status() == TxResult.Status.INSUFFICIENT_FUNDS ? "minesino.error.insufficient_funds" : "minesino.error.transaction_failed";
		int left = count;
		int max = proto.getMaxStackSize();
		while (left > 0) {
			int n = Math.min(max, left);
			ItemStack give = proto.copyWithCount(n);
			inv.add(give);
			left -= n - give.getCount();
			if (!give.isEmpty()) break; // should not happen: capacity was checked in this same tick
		}
		if (left > 0) {
			// Refund exactly what could not be delivered.
			long refund = dev.minesino.util.Money.mulDiv(offer.price(), left, count);
			if (refund > 0) ms.economy.deposit(player.getUUID(), refund, TxType.REFUND, itemId + " x" + left, opId + ":undelivered");
		}
		inv.setChanged();
		player.containerMenu.broadcastChanges();
		final int delivered = count - left;
		ms.economy.updateStats(player.getUUID(), s -> {
			s.purchases += delivered;
			s.purchaseSpent += offer.price();
		});
		out.addProperty("delivered", delivered);
		return null;
	}
}
