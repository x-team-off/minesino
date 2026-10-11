package dev.minesino.mobs;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.pricing.PriceRegistry;
import dev.minesino.pricing.Rational;
import java.math.BigDecimal;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Sale prices of living entities, grouped into ANIMAL / NEUTRAL / HOSTILE / RARE.
 * Golems use recipe formulas based on the item catalogue, e.g.
 * {@code iron_golem = 4 * price(iron_block) + price(carved_pumpkin)}.
 * Entity types not listed have no price and cannot be sold.
 */
public final class MobPriceRegistry {
	public enum Category { ANIMAL, NEUTRAL, HOSTILE, RARE }

	private final Map<String, Rational> prices;
	private final Map<String, Category> categories;
	private final Set<String> banned;

	private MobPriceRegistry(Map<String, Rational> prices, Map<String, Category> categories, Set<String> banned) {
		this.prices = Collections.unmodifiableMap(prices);
		this.categories = Collections.unmodifiableMap(categories);
		this.banned = Collections.unmodifiableSet(banned);
	}

	public static MobPriceRegistry empty() {
		return new MobPriceRegistry(Map.of(), Map.of(), Set.of());
	}

	public static MobPriceRegistry parse(JsonObject root, PriceRegistry items, Predicate<String> knownEntity, List<String> warnings) {
		Map<String, Rational> prices = new HashMap<>();
		Map<String, Category> cats = new HashMap<>();
		Set<String> banned = new HashSet<>();
		if (root.has("banned")) for (JsonElement e : root.getAsJsonArray("banned")) banned.add(e.getAsString());
		if (root.has("categories")) {
			for (Map.Entry<String, JsonElement> c : root.getAsJsonObject("categories").entrySet()) {
				Category cat;
				try {
					cat = Category.valueOf(c.getKey().toUpperCase(java.util.Locale.ROOT));
				} catch (IllegalArgumentException ex) {
					warnings.add("Unknown mob category " + c.getKey());
					continue;
				}
				for (Map.Entry<String, JsonElement> e : c.getValue().getAsJsonObject().entrySet()) {
					Rational p = price(e.getKey(), e.getValue(), knownEntity, warnings);
					if (p != null) {
						prices.put(e.getKey(), p);
						cats.put(e.getKey(), cat);
					}
				}
			}
		}
		Category formulaCat = Category.NEUTRAL;
		if (root.has("formulaCategory")) {
			try {
				formulaCat = Category.valueOf(root.get("formulaCategory").getAsString());
			} catch (RuntimeException ignored) {
				warnings.add("formulaCategory invalid");
			}
		}
		if (root.has("formulas")) {
			for (Map.Entry<String, JsonElement> f : root.getAsJsonObject("formulas").entrySet()) {
				if (!knownEntity.test(f.getKey())) {
					warnings.add("Formula for unknown entity " + f.getKey());
					continue;
				}
				Rational total = Rational.ZERO;
				boolean ok = true;
				for (Map.Entry<String, JsonElement> part : f.getValue().getAsJsonObject().entrySet()) {
					Rational ip = items.unitPrice(part.getKey());
					int n = part.getValue().getAsInt();
					if (ip == null || n <= 0) {
						warnings.add("Formula for " + f.getKey() + " uses unpriced item " + part.getKey());
						ok = false;
						break;
					}
					total = total.add(ip.multiply(n));
				}
				if (ok) {
					prices.put(f.getKey(), total);
					cats.putIfAbsent(f.getKey(), formulaCat);
				}
			}
		}
		if (root.has("overrides")) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("overrides").entrySet()) {
				Rational p = price(e.getKey(), e.getValue(), knownEntity, warnings);
				if (p != null) {
					prices.put(e.getKey(), p);
					cats.putIfAbsent(e.getKey(), Category.NEUTRAL);
				}
			}
		}
		for (String b : banned) prices.remove(b);
		return new MobPriceRegistry(prices, cats, banned);
	}

	private static Rational price(String id, JsonElement v, Predicate<String> known, List<String> warnings) {
		if (!known.test(id)) {
			warnings.add("Price for unknown entity " + id + " ignored");
			return null;
		}
		try {
			BigDecimal d = v.getAsBigDecimal();
			if (d.signum() <= 0 || d.scale() > 2) {
				warnings.add("Invalid mob price for " + id);
				return null;
			}
			return Rational.ofWhole(d);
		} catch (RuntimeException e) {
			warnings.add("Invalid mob price for " + id);
			return null;
		}
	}

	public Rational price(String entityId) {
		return banned.contains(entityId) ? null : prices.get(entityId);
	}

	public Category category(String entityId) {
		return categories.get(entityId);
	}

	public boolean isBanned(String entityId) {
		return banned.contains(entityId);
	}

	public Map<String, Rational> all() {
		return prices;
	}
}
