package dev.minesino.pricing;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Central item price catalogue (selling and shop). Prices come from three sources, in priority:
 * <ol>
 *   <li>explicit {@code overrides} from the price file;</li>
 *   <li>base prices listed per category;</li>
 *   <li>prices derived from recipes: the cheapest recipe cost divided by its output count.</li>
 * </ol>
 * Derivation runs as a fixed-point iteration (like a shortest-path relaxation) instead of
 * recursion, so recipe cycles such as ingot ↔ block ↔ nugget cannot loop forever. After it, for
 * every considered recipe {@code price(output) * count <= cost(ingredients)} holds, which means
 * crafting can never turn sold items into more money ("no infinite profit loops"). Base prices that
 * violate this are clamped down to the recipe cost when {@code clampBaseToRecipeCost} is enabled.
 *
 * <p>Items without any rule have no price: they are neither sold nor bought.
 * Instances are immutable; a reload builds a new registry (the "cache").
 */
public final class PriceRegistry {
	private static final int MAX_ITERATIONS = 128;

	private final Map<String, Rational> prices;
	private final Map<String, PriceCategory> categories;
	private final Set<String> banned;
	private final List<String> warnings;
	private final Rational sellFallback;
	private final Set<String> fallbackNamespaces;

	private PriceRegistry(Map<String, Rational> prices, Map<String, PriceCategory> categories, Set<String> banned, List<String> warnings,
						  Rational sellFallback, Set<String> fallbackNamespaces) {
		this.prices = Collections.unmodifiableMap(prices);
		this.categories = Collections.unmodifiableMap(categories);
		this.banned = Collections.unmodifiableSet(banned);
		this.warnings = List.copyOf(warnings);
		this.sellFallback = sellFallback;
		this.fallbackNamespaces = Set.copyOf(fallbackNamespaces);
	}

	public static PriceRegistry empty() {
		return new PriceRegistry(Map.of(), Map.of(), Set.of(), List.of(), null, Set.of());
	}

	/** Parsed price file. */
	public static final class Table {
		public final Map<String, Rational> base = new LinkedHashMap<>();
		public final Map<String, PriceCategory> baseCategory = new HashMap<>();
		public final Map<String, Rational> overrides = new LinkedHashMap<>();
		public final Map<String, PriceCategory> categoryOverrides = new HashMap<>();
		public final Set<String> banned = new HashSet<>();
		public boolean deriveFromRecipes = true;
		public boolean clampBaseToRecipeCost = true;
		/** Base prices below this are raised to it (null = no minimum). */
		public Rational minimumPrice;
		/** Sell-only price for items of the listed namespaces that have no rule; never used for buying. */
		public Rational sellFallbackPrice;
		public final Set<String> fallbackNamespaces = new HashSet<>(Set.of("minecraft"));

		/** Parses and validates the JSON price file; problems are reported, bad entries skipped. */
		public static Table parse(JsonObject root, Predicate<String> knownItem, List<String> warnings) {
			Table t = new Table();
			if (root.has("categories") && root.get("categories").isJsonObject()) {
				for (Map.Entry<String, JsonElement> c : root.getAsJsonObject("categories").entrySet()) {
					PriceCategory cat = PriceCategory.parse(c.getKey(), null);
					if (cat == null) {
						warnings.add("Unknown price category '" + c.getKey() + "' ignored");
						continue;
					}
					if (!c.getValue().isJsonObject()) continue;
					for (Map.Entry<String, JsonElement> e : c.getValue().getAsJsonObject().entrySet()) {
						Rational p = parsePrice(e.getKey(), e.getValue(), knownItem, warnings);
						if (p != null) {
							t.base.put(e.getKey(), p);
							t.baseCategory.put(e.getKey(), cat);
						}
					}
				}
			}
			if (root.has("overrides") && root.get("overrides").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("overrides").entrySet()) {
					Rational p = parsePrice(e.getKey(), e.getValue(), knownItem, warnings);
					if (p != null) t.overrides.put(e.getKey(), p);
				}
			}
			if (root.has("categoryOverrides") && root.get("categoryOverrides").isJsonObject()) {
				for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("categoryOverrides").entrySet()) {
					PriceCategory cat = PriceCategory.parse(e.getValue().getAsString(), null);
					if (cat == null) warnings.add("categoryOverrides." + e.getKey() + ": unknown category");
					else t.categoryOverrides.put(e.getKey(), cat);
				}
			}
			if (root.has("banned") && root.get("banned").isJsonArray()) {
				for (JsonElement e : root.getAsJsonArray("banned")) t.banned.add(e.getAsString());
			}
			if (root.has("deriveFromRecipes")) t.deriveFromRecipes = root.get("deriveFromRecipes").getAsBoolean();
			if (root.has("clampBaseToRecipeCost")) t.clampBaseToRecipeCost = root.get("clampBaseToRecipeCost").getAsBoolean();
			t.minimumPrice = optionalPrice(root, "minimumPrice", warnings);
			t.sellFallbackPrice = optionalPrice(root, "sellFallbackPrice", warnings);
			if (root.has("fallbackNamespaces") && root.get("fallbackNamespaces").isJsonArray()) {
				t.fallbackNamespaces.clear();
				for (JsonElement e : root.getAsJsonArray("fallbackNamespaces")) t.fallbackNamespaces.add(e.getAsString());
			}
			if (t.minimumPrice != null) {
				for (Map.Entry<String, Rational> e : t.base.entrySet()) {
					if (e.getValue().compareTo(t.minimumPrice) < 0) e.setValue(t.minimumPrice);
				}
			}
			return t;
		}

		private static Rational optionalPrice(JsonObject root, String key, List<String> warnings) {
			if (!root.has(key) || root.get(key).isJsonNull()) return null;
			try {
				BigDecimal d = root.get(key).getAsBigDecimal();
				if (d.signum() <= 0 || d.scale() > 2) {
					warnings.add(key + " must be positive with at most 2 decimals; ignored");
					return null;
				}
				return Rational.ofWhole(d);
			} catch (RuntimeException e) {
				warnings.add(key + " is not a number; ignored");
				return null;
			}
		}

		private static Rational parsePrice(String id, JsonElement v, Predicate<String> knownItem, List<String> warnings) {
			if (!knownItem.test(id)) {
				warnings.add("Price for unknown item '" + id + "' ignored");
				return null;
			}
			try {
				BigDecimal d = v.getAsBigDecimal();
				if (d.signum() <= 0 || d.scale() > 4 || d.compareTo(BigDecimal.valueOf(1_000_000_000L)) > 0) {
					warnings.add("Invalid price " + d + " for " + id + " (must be > 0, at most 4 decimals)");
					return null;
				}
				return Rational.ofWhole(d);
			} catch (RuntimeException e) {
				warnings.add("Price for " + id + " is not a number");
				return null;
			}
		}
	}

	/**
	 * @param guessCategory category for derived items (may return null); typically checks whether the
	 *                      item is a tool, armor piece or a decorative block
	 */
	public static PriceRegistry build(Table table, List<RecipeEdge> recipes, Function<String, PriceCategory> guessCategory) {
		List<String> warnings = new ArrayList<>();
		Map<String, Rational> p = new HashMap<>(table.base);
		Map<String, PriceCategory> cat = new HashMap<>(table.baseCategory);
		p.putAll(table.overrides);
		for (String b : table.banned) p.remove(b);
		Set<String> fixed = new HashSet<>(table.overrides.keySet());
		Map<String, String> bestIngredient = new HashMap<>();

		if (table.deriveFromRecipes) {
			Set<String> lastChanged = new HashSet<>();
			int iter = 0;
			boolean changed = true;
			while (changed && iter < MAX_ITERATIONS) {
				changed = false;
				lastChanged.clear();
				iter++;
				for (RecipeEdge r : recipes) {
					if (table.banned.contains(r.output()) || fixed.contains(r.output())) continue;
					Rational cost = Rational.ZERO;
					String priciest = null;
					Rational priciestValue = null;
					boolean ok = !r.slots().isEmpty();
					for (List<String> slot : r.slots()) {
						Rational best = null;
						String bestId = null;
						for (String alt : slot) {
							if (table.banned.contains(alt)) continue;
							Rational ap = p.get(alt);
							if (ap != null && (best == null || ap.compareTo(best) < 0)) {
								best = ap;
								bestId = alt;
							}
						}
						if (best == null) {
							ok = false;
							break;
						}
						cost = cost.add(best);
						if (priciestValue == null || best.compareTo(priciestValue) > 0) {
							priciestValue = best;
							priciest = bestId;
						}
					}
					if (!ok) continue;
					Rational unit = cost.divide(r.count());
					Rational current = p.get(r.output());
					if (current == null || unit.compareTo(current) < 0) {
						if (current != null && table.base.containsKey(r.output())) {
							if (!table.clampBaseToRecipeCost) continue;
							warnings.add("Base price of " + r.output() + " (" + current + ") exceeds recipe cost " + unit
								+ " via " + r.source() + "; clamped to prevent craft-and-sell profit");
						}
						p.put(r.output(), unit);
						bestIngredient.put(r.output(), priciest);
						changed = true;
						lastChanged.add(r.output());
					}
				}
			}
			if (changed) {
				// A recipe cycle keeps making something cheaper: it produces value from nothing.
				for (String id : lastChanged) {
					warnings.add("Recipe cycle creates value for " + id + "; item left without price");
					p.remove(id);
				}
			}
		}

		for (String id : p.keySet()) {
			if (table.categoryOverrides.containsKey(id)) {
				cat.put(id, table.categoryOverrides.get(id));
			} else if (!cat.containsKey(id)) {
				PriceCategory g = guessCategory == null ? null : guessCategory.apply(id);
				if (g == null) {
					String ing = bestIngredient.get(id);
					// Walk ingredient chain to inherit the category of the main raw material.
					for (int depth = 0; ing != null && g == null && depth < 16; depth++) {
						g = table.baseCategory.get(ing);
						if (g == null) g = table.categoryOverrides.get(ing);
						ing = bestIngredient.get(ing);
					}
				}
				cat.put(id, g == null ? PriceCategory.SPECIAL : g);
			}
		}
		for (String b : table.banned) cat.put(b, PriceCategory.FORBIDDEN);
		return new PriceRegistry(p, cat, new HashSet<>(table.banned), warnings, table.sellFallbackPrice, table.fallbackNamespaces);
	}

	// ----------------------------------------------------------------- queries

	/** Catalogue price for one item, in exact minor units; null when the item has no price. */
	public Rational unitPrice(String id) {
		return banned.contains(id) ? null : prices.get(id);
	}

	public boolean isBanned(String id) {
		return banned.contains(id);
	}

	public PriceCategory category(String id) {
		return categories.getOrDefault(id, banned.contains(id) ? PriceCategory.FORBIDDEN : PriceCategory.SPECIAL);
	}

	public Map<String, Rational> all() {
		return prices;
	}

	public List<String> warnings() {
		return warnings;
	}

	/**
	 * Price used when a player sells: the catalogue price, or the fallback price for vanilla items
	 * without a rule. The fallback is sell-only so buying cheap containers (water buckets...) and
	 * selling their parts can never be profitable.
	 */
	public Rational sellUnitPrice(String id) {
		Rational u = unitPrice(id);
		if (u != null || banned.contains(id) || sellFallback == null || id.endsWith("_spawn_egg")) return u;
		int colon = id.indexOf(':');
		String ns = colon < 0 ? "minecraft" : id.substring(0, colon);
		return fallbackNamespaces.contains(ns) ? sellFallback : null;
	}

	/** Money paid to a player selling {@code count} items (rounded down). 0 if not sellable. */
	public long sellValue(String id, int count, BigDecimal sellMultiplier) {
		Rational u = sellUnitPrice(id);
		if (u == null || count <= 0) return 0;
		return u.multiply(count).multiply(sellMultiplier).floor();
	}

	/** Money charged for buying {@code count} items (rounded up). -1 if not purchasable. */
	public long buyPrice(String id, int count, BigDecimal buyMultiplier, Rational override) {
		if (count <= 0 || banned.contains(id)) return -1;
		if (override != null) return override.multiply(count).ceil();
		Rational u = unitPrice(id);
		if (u == null) return -1;
		long v = u.multiply(count).multiply(buyMultiplier).ceil();
		return Math.max(1, v);
	}
}
