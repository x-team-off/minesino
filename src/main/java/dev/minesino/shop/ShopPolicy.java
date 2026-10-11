package dev.minesino.shop;

import dev.minesino.config.MineSinoConfig;
import dev.minesino.mobs.MobPriceRegistry;
import dev.minesino.pricing.PriceCategory;
import dev.minesino.pricing.PriceRegistry;
import dev.minesino.pricing.Rational;
import java.util.Set;

/**
 * Decides which items can be bought ("withdrawn") and for how much. Safe by default: unknown
 * namespaces, banned items, administrative blocks and spawn eggs not explicitly allowed are refused.
 */
public final class ShopPolicy {
	/** Never purchasable whatever the configuration says. */
	public static final Set<String> ALWAYS_BANNED = Set.of(
		"minecraft:bedrock", "minecraft:command_block", "minecraft:chain_command_block", "minecraft:repeating_command_block",
		"minecraft:command_block_minecart", "minecraft:structure_block", "minecraft:structure_void", "minecraft:barrier",
		"minecraft:jigsaw", "minecraft:light", "minecraft:debug_stick", "minecraft:knowledge_book",
		"minecraft:test_block", "minecraft:test_instance_block", "minecraft:spawner", "minecraft:trial_spawner",
		"minecraft:vault", "minecraft:end_portal_frame", "minecraft:reinforced_deepslate", "minesino:tablet");

	private ShopPolicy() {}

	/** Resolved offer, or {@code price < 0} with an error key. */
	public record Offer(String itemId, long unitPriceHint, long price, String error) {
		public boolean ok() { return error == null; }
	}

	/**
	 * @param spawnEggEntity entity id when {@code itemId} is a spawn egg, otherwise null
	 */
	public static Offer quote(String itemId, int count, String spawnEggEntity, PriceRegistry prices, MobPriceRegistry mobs, MineSinoConfig cfg) {
		if (!cfg.shop.enabled) return fail(itemId, "minesino.error.shop_disabled");
		if (count <= 0 || count > cfg.shop.maxPurchaseCount) return fail(itemId, "minesino.error.invalid_params");
		if (ALWAYS_BANNED.contains(itemId) || prices.isBanned(itemId)) return fail(itemId, "minesino.error.item_forbidden");
		if (spawnEggEntity != null) return quoteEgg(itemId, count, spawnEggEntity, mobs, cfg);
		String ns = itemId.contains(":") ? itemId.substring(0, itemId.indexOf(':')) : "minecraft";
		if (!cfg.shop.allowedNamespaces.contains(ns) && !cfg.shop.allowItems.contains(itemId)) return fail(itemId, "minesino.error.item_forbidden");
		PriceCategory cat = prices.category(itemId);
		if (cat == PriceCategory.FORBIDDEN || cfg.shop.disabledCategories.contains(cat.name())) return fail(itemId, "minesino.error.item_forbidden");
		Rational override = cfg.shop.buyPriceOverrides.containsKey(itemId) ? Rational.ofWhole(cfg.shop.buyPriceOverrides.get(itemId)) : null;
		long total = prices.buyPrice(itemId, count, cfg.shop.buyMultiplier, override);
		if (total <= 0) return fail(itemId, "minesino.error.not_for_sale");
		// Safety net: buying must never be cheaper than selling the same items back.
		long resale = prices.sellValue(itemId, count, cfg.selling.sellMultiplier);
		if (total < resale) total = resale;
		long unit = prices.buyPrice(itemId, 1, cfg.shop.buyMultiplier, override);
		return new Offer(itemId, unit, total, null);
	}

	private static Offer quoteEgg(String itemId, int count, String entity, MobPriceRegistry mobs, MineSinoConfig cfg) {
		if (!cfg.spawnEggs.enabled || !cfg.spawnEggs.allowed.contains(entity) || mobs.isBanned(entity)) return fail(itemId, "minesino.error.egg_forbidden");
		Rational unit;
		if (cfg.spawnEggs.prices.containsKey(entity)) {
			unit = Rational.ofWhole(cfg.spawnEggs.prices.get(entity));
		} else {
			Rational mob = mobs.price(entity);
			if (mob == null) return fail(itemId, "minesino.error.egg_forbidden");
			unit = mob.multiply(cfg.spawnEggs.priceMultiplier);
		}
		Rational min = Rational.ofWhole(cfg.spawnEggs.minimumPrice);
		if (unit.compareTo(min) < 0) unit = min;
		// An egg must never cost less than the mob sells for, otherwise buy-spawn-sell prints money.
		Rational mob = mobs.price(entity);
		if (mob != null && unit.compareTo(mob) < 0) unit = mob;
		long total = unit.multiply(count).ceil();
		return new Offer(itemId, unit.ceil(), total, null);
	}

	private static Offer fail(String itemId, String key) {
		return new Offer(itemId, -1, -1, key);
	}
}
