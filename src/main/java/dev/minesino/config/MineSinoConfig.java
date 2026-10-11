package dev.minesino.config;

import dev.minesino.util.Money;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Server configuration ({@code config/minesino/minesino.json}). Money values are written in whole
 * currency units with up to two decimals and converted to exact minor units on load.
 * Field names are the JSON keys; see {@link ConfigIO} for the comments written into the file.
 */
public final class MineSinoConfig {
	public Currency currency = new Currency();
	public Casino casino = new Casino();
	public Selling selling = new Selling();
	public Shop shop = new Shop();
	public Mobs mobs = new Mobs();
	public SpawnEggs spawnEggs = new SpawnEggs();
	public Permissions permissions = new Permissions();
	public Limits limits = new Limits();
	public Storage storage = new Storage();
	public Logging logging = new Logging();

	public static final class Currency {
		public String symbol = "MS";
		public int displayDecimals = 2;
		public BigDecimal startingBalance = BigDecimal.ZERO;
	}

	public static final class Casino {
		public boolean enabled = true;
		public BigDecimal minBet = new BigDecimal("1");
		public BigDecimal maxBet = new BigDecimal("1000000");
		public List<BigDecimal> quickBets = new ArrayList<>(List.of(new BigDecimal("100"), new BigDecimal("500"),
			new BigDecimal("1000"), new BigDecimal("5000"), new BigDecimal("10000")));
		public Map<String, Boolean> enabledGames = defaultGames();
		public Slots slots = new Slots();
		public Blackjack blackjack = new Blackjack();
		public Plinko plinko = new Plinko();
		public Dice dice = new Dice();
		public Upgrade upgrade = new Upgrade();
		public Coinflip coinflip = new Coinflip();
		public Mines mines = new Mines();
		public Crash crash = new Crash();
	}

	static Map<String, Boolean> defaultGames() {
		Map<String, Boolean> m = new LinkedHashMap<>();
		for (String g : new String[] {"roulette", "slots", "blackjack", "plinko", "dice", "upgrade", "coinflip", "mines", "crash", "wheel", "hilo", "keno"}) m.put(g, true);
		return m;
	}

	public static final class Coinflip {
		/** Win pays 2x minus this edge. */
		public BigDecimal houseEdgePercent = new BigDecimal("2");
	}

	public static final class Mines {
		/** Multiplier after k safe tiles = (1 - edge) * prod (25-i)/(25-mines-i). */
		public BigDecimal houseEdgePercent = new BigDecimal("2");
	}

	public static final class Crash {
		/** P(crash point >= target) = (1 - edge) / target. */
		public BigDecimal houseEdgePercent = new BigDecimal("3");
		public BigDecimal minTarget = new BigDecimal("1.01");
		public BigDecimal maxTarget = new BigDecimal("1000");
	}

	public static final class Slots {
		/** Reel symbols. Weight = copies on each 32-stop reel strip; pay3 = line multiplier for three in a row. */
		public List<SlotSymbol> symbols = new ArrayList<>(List.of(
			new SlotSymbol("nether_star", 1, new BigDecimal("1000")),
			new SlotSymbol("diamond", 2, new BigDecimal("250")),
			new SlotSymbol("emerald", 3, new BigDecimal("100")),
			new SlotSymbol("gold_ingot", 5, new BigDecimal("40")),
			new SlotSymbol("iron_ingot", 6, new BigDecimal("25")),
			new SlotSymbol("redstone", 7, new BigDecimal("15")),
			new SlotSymbol("apple", 8, new BigDecimal("10"))));
		/** Symbol that also pays when it lands on the first two reels of a line. */
		public String pairSymbol = "apple";
		public BigDecimal pairMultiplier = new BigDecimal("3");
	}

	public static final class SlotSymbol {
		public String id;
		public int weight;
		public BigDecimal pay3;

		public SlotSymbol() {}

		public SlotSymbol(String id, int weight, BigDecimal pay3) {
			this.id = id;
			this.weight = weight;
			this.pay3 = pay3;
		}
	}

	public static final class Blackjack {
		public int decks = 6;
		public boolean dealerHitsSoft17 = false;
		/** Natural blackjack pays numerator:denominator (3:2 by default). */
		public int blackjackPayNumerator = 3;
		public int blackjackPayDenominator = 2;
	}

	public static final class Plinko {
		/** Bin multipliers per "risk_rows". Each list must have rows+1 entries. */
		public Map<String, List<BigDecimal>> multipliers = defaultPlinko();
	}

	public static final class Dice {
		/** House edge in percent applied to the fair multiplier 6/k. */
		public BigDecimal houseEdgePercent = new BigDecimal("2");
	}

	public static final class Upgrade {
		public BigDecimal commissionPercent = new BigDecimal("5");
		public BigDecimal minMultiplier = new BigDecimal("1.1");
		public BigDecimal maxMultiplier = new BigDecimal("100");
		public boolean allowItems = true;
	}

	public static final class Selling {
		public boolean enabled = true;
		/** Allow selling items with custom data (enchantments, damage, names...). They sell at base price. */
		public boolean allowModifiedItems = false;
		/** Fraction of the catalogue price paid when a player sells (1.0 = full price). */
		public BigDecimal sellMultiplier = new BigDecimal("1.0");
	}

	public static final class Shop {
		public boolean enabled = true;
		/** Buy price = sell price * buyMultiplier. Keep above 1 so buying and reselling never profits. */
		public BigDecimal buyMultiplier = new BigDecimal("2.0");
		/** Item namespaces sold automatically. Items from other mods need explicit allowItems entries. */
		public List<String> allowedNamespaces = new ArrayList<>(List.of("minecraft"));
		public List<String> allowItems = new ArrayList<>();
		public List<String> disabledCategories = new ArrayList<>(List.of("FORBIDDEN"));
		/** Explicit buy prices (whole MS) that replace the computed price. */
		public Map<String, BigDecimal> buyPriceOverrides = new LinkedHashMap<>();
		public int maxPurchaseCount = 1728;
	}

	public static final class Mobs {
		public boolean enabled = true;
		public boolean requireConfirmation = true;
		/** When true, Shift+RMB sells only while the tablet slot is selected (avoids clashing with vanilla interactions). */
		public boolean requireTablet = true;
		public boolean protectNamed = true;
		public boolean protectTamed = true;
		public boolean protectLeashed = true;
		public boolean protectWithPassengers = true;
		public boolean protectVillagers = true;
		public boolean allowBabies = false;
		/** Price factor for animals born from breeding (anti breeding-farm abuse). */
		public BigDecimal bredMultiplier = new BigDecimal("0.25");
		/** Price factor for iron golems that were not built by a player (iron farms). */
		public BigDecimal naturalGolemMultiplier = new BigDecimal("0.1");
		public double maxDistance = 6.0;
		/** Seconds a confirmation offer stays valid. */
		public int offerTimeoutSeconds = 20;
	}

	public static final class SpawnEggs {
		public boolean enabled = true;
		/** Egg price = mob sale price * priceMultiplier unless an explicit price is set. */
		public BigDecimal priceMultiplier = new BigDecimal("3.0");
		public BigDecimal minimumPrice = new BigDecimal("100");
		/** Entity ids whose eggs may be bought. Unknown eggs are never sold. */
		public List<String> allowed = new ArrayList<>(List.of(
			"minecraft:cow", "minecraft:pig", "minecraft:sheep", "minecraft:chicken", "minecraft:rabbit",
			"minecraft:horse", "minecraft:donkey", "minecraft:llama", "minecraft:goat", "minecraft:cat",
			"minecraft:wolf", "minecraft:fox", "minecraft:parrot", "minecraft:bee", "minecraft:axolotl",
			"minecraft:frog", "minecraft:turtle", "minecraft:armadillo", "minecraft:camel", "minecraft:mooshroom",
			"minecraft:panda", "minecraft:sniffer", "minecraft:squid", "minecraft:glow_squid", "minecraft:cod",
			"minecraft:salmon", "minecraft:tropical_fish", "minecraft:dolphin", "minecraft:strider",
			"minecraft:zombie", "minecraft:skeleton", "minecraft:spider", "minecraft:creeper", "minecraft:slime"));
		public Map<String, BigDecimal> prices = new LinkedHashMap<>();
	}

	public static final class Permissions {
		/** Permission level that grants balance administration (2 = game masters / ops). */
		public int adminLevel = 2;
		/** Extra players (UUIDs) allowed to use admin commands without being operators. */
		public List<String> adminPlayers = new ArrayList<>();
	}

	public static final class Limits {
		/** Minimum ticks between two economic requests of the same player. */
		public int requestCooldownTicks = 2;
		public int maxRequestsPerSecond = 12;
		public int maxSellLines = 64;
	}

	public static final class Storage {
		public int historyLimit = 500;
		public int backups = 5;
	}

	public static final class Logging {
		public boolean journal = true;
		public boolean security = true;
	}

	// --------------------------------------------------------------- derived values

	public long startingBalanceUnits() { return units(currency.startingBalance); }
	public long minBetUnits() { return units(casino.minBet); }
	public long maxBetUnits() { return units(casino.maxBet); }

	public static long units(BigDecimal whole) {
		return whole.multiply(BigDecimal.valueOf(Money.SCALE)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
	}

	/** Basis points (1/10000) of a percentage value, e.g. 2.5% -> 250. */
	public static long bps(BigDecimal percent) {
		return percent.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
	}

	/** Hundredths of a multiplier, e.g. 1.25x -> 125. */
	public static long hundredths(BigDecimal mult) {
		return mult.multiply(BigDecimal.valueOf(100)).setScale(0, java.math.RoundingMode.FLOOR).longValueExact();
	}

	public boolean gameEnabled(String id) {
		return casino.enabled && casino.enabledGames.getOrDefault(id, false);
	}

	// --------------------------------------------------------------- validation

	/** Clamps or resets invalid values. Every correction is reported through {@code warn}. */
	public void validate(Consumer<String> warn) {
		MineSinoConfig d = new MineSinoConfig();
		if (currency == null) { currency = d.currency; warn.accept("currency section missing, using defaults"); }
		if (currency.symbol == null || currency.symbol.length() > 8) { warn.accept("currency.symbol invalid, using MS"); currency.symbol = "MS"; }
		if (currency.displayDecimals < 0 || currency.displayDecimals > 2) { warn.accept("currency.displayDecimals must be 0..2"); currency.displayDecimals = Math.clamp(currency.displayDecimals, 0, 2); }
		currency.startingBalance = money(currency.startingBalance, d.currency.startingBalance, "currency.startingBalance", warn);

		if (casino == null) { casino = d.casino; warn.accept("casino section missing, using defaults"); }
		casino.minBet = money(casino.minBet, d.casino.minBet, "casino.minBet", warn);
		casino.maxBet = money(casino.maxBet, d.casino.maxBet, "casino.maxBet", warn);
		if (casino.minBet.signum() <= 0) { warn.accept("casino.minBet must be positive"); casino.minBet = d.casino.minBet; }
		if (casino.maxBet.compareTo(casino.minBet) < 0) { warn.accept("casino.maxBet < minBet, resetting"); casino.maxBet = casino.minBet.max(d.casino.maxBet); }
		if (casino.quickBets == null || casino.quickBets.isEmpty()) casino.quickBets = d.casino.quickBets;
		casino.quickBets.removeIf(b -> b == null || b.signum() <= 0 || b.scale() > 2);
		if (casino.enabledGames == null) casino.enabledGames = d.casino.enabledGames;
		for (Map.Entry<String, Boolean> e : defaultGames().entrySet()) casino.enabledGames.putIfAbsent(e.getKey(), e.getValue());
		if (casino.coinflip == null) casino.coinflip = d.casino.coinflip;
		casino.coinflip.houseEdgePercent = percent(casino.coinflip.houseEdgePercent, d.casino.coinflip.houseEdgePercent, 0, 20, "casino.coinflip.houseEdgePercent", warn);
		if (casino.mines == null) casino.mines = d.casino.mines;
		casino.mines.houseEdgePercent = percent(casino.mines.houseEdgePercent, d.casino.mines.houseEdgePercent, 0, 20, "casino.mines.houseEdgePercent", warn);
		if (casino.crash == null) casino.crash = d.casino.crash;
		casino.crash.houseEdgePercent = percent(casino.crash.houseEdgePercent, d.casino.crash.houseEdgePercent, 0, 20, "casino.crash.houseEdgePercent", warn);
		if (casino.crash.minTarget == null || casino.crash.minTarget.compareTo(new BigDecimal("1.01")) < 0) casino.crash.minTarget = d.casino.crash.minTarget;
		if (casino.crash.maxTarget == null || casino.crash.maxTarget.compareTo(casino.crash.minTarget) <= 0 || casino.crash.maxTarget.compareTo(new BigDecimal("10000")) > 0) casino.crash.maxTarget = d.casino.crash.maxTarget;
		casino.crash.minTarget = casino.crash.minTarget.setScale(2, java.math.RoundingMode.FLOOR);
		casino.crash.maxTarget = casino.crash.maxTarget.setScale(2, java.math.RoundingMode.FLOOR);
		if (casino.slots == null) casino.slots = d.casino.slots;
		validateSlots(d, warn);
		if (casino.blackjack == null) casino.blackjack = d.casino.blackjack;
		if (casino.blackjack.decks < 1 || casino.blackjack.decks > 8) { warn.accept("casino.blackjack.decks must be 1..8"); casino.blackjack.decks = 6; }
		if (casino.blackjack.blackjackPayNumerator < 1 || casino.blackjack.blackjackPayDenominator < 1
			|| casino.blackjack.blackjackPayNumerator * 2 < casino.blackjack.blackjackPayDenominator
			|| casino.blackjack.blackjackPayNumerator > 3 * casino.blackjack.blackjackPayDenominator) {
			warn.accept("casino.blackjack natural payout invalid, using 3:2");
			casino.blackjack.blackjackPayNumerator = 3;
			casino.blackjack.blackjackPayDenominator = 2;
		}
		if (casino.plinko == null || casino.plinko.multipliers == null) casino.plinko = d.casino.plinko;
		validatePlinko(d, warn);
		if (casino.dice == null) casino.dice = d.casino.dice;
		casino.dice.houseEdgePercent = percent(casino.dice.houseEdgePercent, d.casino.dice.houseEdgePercent, 0, 20, "casino.dice.houseEdgePercent", warn);
		if (casino.upgrade == null) casino.upgrade = d.casino.upgrade;
		casino.upgrade.commissionPercent = percent(casino.upgrade.commissionPercent, d.casino.upgrade.commissionPercent, 0, 50, "casino.upgrade.commissionPercent", warn);
		if (casino.upgrade.minMultiplier == null || casino.upgrade.minMultiplier.compareTo(new BigDecimal("1.01")) < 0) {
			warn.accept("casino.upgrade.minMultiplier must be >= 1.01");
			casino.upgrade.minMultiplier = d.casino.upgrade.minMultiplier;
		}
		if (casino.upgrade.maxMultiplier == null || casino.upgrade.maxMultiplier.compareTo(casino.upgrade.minMultiplier) <= 0
			|| casino.upgrade.maxMultiplier.compareTo(new BigDecimal("10000")) > 0) {
			warn.accept("casino.upgrade.maxMultiplier invalid");
			casino.upgrade.maxMultiplier = d.casino.upgrade.maxMultiplier;
		}
		casino.upgrade.minMultiplier = casino.upgrade.minMultiplier.setScale(2, java.math.RoundingMode.FLOOR);
		casino.upgrade.maxMultiplier = casino.upgrade.maxMultiplier.setScale(2, java.math.RoundingMode.FLOOR);

		if (selling == null) selling = d.selling;
		selling.sellMultiplier = factor(selling.sellMultiplier, d.selling.sellMultiplier, "selling.sellMultiplier", warn);
		if (shop == null) shop = d.shop;
		shop.buyMultiplier = factor(shop.buyMultiplier, d.shop.buyMultiplier, "shop.buyMultiplier", warn);
		if (shop.buyMultiplier.compareTo(selling.sellMultiplier) < 0) {
			warn.accept("shop.buyMultiplier is lower than selling.sellMultiplier: buy-and-resell would print money; raising it");
			shop.buyMultiplier = selling.sellMultiplier;
		}
		if (shop.allowedNamespaces == null) shop.allowedNamespaces = d.shop.allowedNamespaces;
		if (shop.allowItems == null) shop.allowItems = new ArrayList<>();
		if (shop.disabledCategories == null) shop.disabledCategories = d.shop.disabledCategories;
		if (!shop.disabledCategories.contains("FORBIDDEN")) shop.disabledCategories.add("FORBIDDEN");
		if (shop.buyPriceOverrides == null) shop.buyPriceOverrides = new LinkedHashMap<>();
		shop.buyPriceOverrides.entrySet().removeIf(e -> {
			boolean bad = e.getValue() == null || e.getValue().signum() <= 0 || e.getValue().scale() > 2;
			if (bad) warn.accept("shop.buyPriceOverrides." + e.getKey() + " invalid, ignored");
			return bad;
		});
		shop.maxPurchaseCount = Math.clamp(shop.maxPurchaseCount, 1, 64 * 36);

		if (mobs == null) mobs = d.mobs;
		mobs.bredMultiplier = factor(mobs.bredMultiplier, d.mobs.bredMultiplier, "mobs.bredMultiplier", warn);
		mobs.naturalGolemMultiplier = factor(mobs.naturalGolemMultiplier, d.mobs.naturalGolemMultiplier, "mobs.naturalGolemMultiplier", warn);
		if (!(mobs.maxDistance > 0 && mobs.maxDistance <= 16)) { warn.accept("mobs.maxDistance must be in (0,16]"); mobs.maxDistance = 6; }
		mobs.offerTimeoutSeconds = Math.clamp(mobs.offerTimeoutSeconds, 3, 120);

		if (spawnEggs == null) spawnEggs = d.spawnEggs;
		spawnEggs.priceMultiplier = factor(spawnEggs.priceMultiplier, d.spawnEggs.priceMultiplier, "spawnEggs.priceMultiplier", warn);
		if (spawnEggs.priceMultiplier.compareTo(BigDecimal.ONE) < 0) {
			warn.accept("spawnEggs.priceMultiplier below 1 lets players buy eggs and sell mobs for profit; using 1");
			spawnEggs.priceMultiplier = BigDecimal.ONE;
		}
		spawnEggs.minimumPrice = money(spawnEggs.minimumPrice, d.spawnEggs.minimumPrice, "spawnEggs.minimumPrice", warn);
		if (spawnEggs.allowed == null) spawnEggs.allowed = new ArrayList<>();
		if (spawnEggs.prices == null) spawnEggs.prices = new LinkedHashMap<>();

		if (permissions == null) permissions = d.permissions;
		permissions.adminLevel = Math.clamp(permissions.adminLevel, 1, 4);
		if (permissions.adminPlayers == null) permissions.adminPlayers = new ArrayList<>();
		if (limits == null) limits = d.limits;
		limits.requestCooldownTicks = Math.clamp(limits.requestCooldownTicks, 0, 40);
		limits.maxRequestsPerSecond = Math.clamp(limits.maxRequestsPerSecond, 1, 100);
		limits.maxSellLines = Math.clamp(limits.maxSellLines, 1, 128);
		if (storage == null) storage = d.storage;
		storage.historyLimit = Math.clamp(storage.historyLimit, 20, 5000);
		storage.backups = Math.clamp(storage.backups, 0, 20);
		if (logging == null) logging = d.logging;
	}

	private void validateSlots(MineSinoConfig d, Consumer<String> warn) {
		Slots s = casino.slots;
		boolean ok = s.symbols != null && s.symbols.size() >= 3 && s.symbols.size() <= 12;
		int total = 0;
		if (ok) {
			for (SlotSymbol sym : s.symbols) {
				if (sym == null || sym.id == null || sym.weight < 1 || sym.pay3 == null || sym.pay3.signum() < 0 || sym.pay3.scale() > 2) {
					ok = false;
					break;
				}
				total += sym.weight;
			}
		}
		if (ok && total != 32) ok = false;
		if (!ok) {
			warn.accept("casino.slots.symbols invalid (need 3..12 symbols, weights summing to 32); using defaults");
			casino.slots = d.casino.slots;
			return;
		}
		if (s.pairMultiplier == null || s.pairMultiplier.signum() < 0 || s.pairMultiplier.scale() > 2) s.pairMultiplier = d.casino.slots.pairMultiplier;
	}

	private void validatePlinko(MineSinoConfig d, Consumer<String> warn) {
		Map<String, List<BigDecimal>> fixed = new LinkedHashMap<>();
		for (Map.Entry<String, List<BigDecimal>> e : defaultPlinko().entrySet()) {
			List<BigDecimal> v = casino.plinko.multipliers.get(e.getKey());
			int rows = Integer.parseInt(e.getKey().substring(e.getKey().indexOf('_') + 1));
			boolean valid = v != null && v.size() == rows + 1 && v.stream().allMatch(m -> m != null && m.signum() >= 0 && m.scale() <= 2);
			if (!valid) {
				warn.accept("casino.plinko.multipliers." + e.getKey() + " invalid, using defaults");
				v = e.getValue();
			}
			fixed.put(e.getKey(), v);
		}
		casino.plinko.multipliers = fixed;
	}

	private static BigDecimal money(BigDecimal v, BigDecimal def, String name, Consumer<String> warn) {
		if (v == null || v.signum() < 0 || v.scale() > 2 || v.compareTo(BigDecimal.valueOf(Money.MAX_AMOUNT / Money.SCALE)) > 0) {
			warn.accept(name + " invalid (" + v + "), using " + def);
			return def;
		}
		return v;
	}

	private static BigDecimal factor(BigDecimal v, BigDecimal def, String name, Consumer<String> warn) {
		if (v == null || v.signum() < 0 || v.compareTo(BigDecimal.valueOf(100)) > 0) {
			warn.accept(name + " invalid (" + v + "), using " + def);
			return def;
		}
		return v.setScale(Math.min(4, Math.max(v.scale(), 0)), java.math.RoundingMode.FLOOR);
	}

	private static BigDecimal percent(BigDecimal v, BigDecimal def, int min, int max, String name, Consumer<String> warn) {
		if (v == null || v.compareTo(BigDecimal.valueOf(min)) < 0 || v.compareTo(BigDecimal.valueOf(max)) > 0) {
			warn.accept(name + " must be within " + min + ".." + max + ", using " + def);
			return def;
		}
		return v.setScale(2, java.math.RoundingMode.FLOOR);
	}

	static Map<String, List<BigDecimal>> defaultPlinko() {
		Map<String, List<BigDecimal>> m = new LinkedHashMap<>();
		m.put("low_8", bd("5.6", "2.1", "1.1", "1", "0.5", "1", "1.1", "2.1", "5.6"));
		m.put("medium_8", bd("13", "3", "1.3", "0.7", "0.4", "0.7", "1.3", "3", "13"));
		m.put("high_8", bd("29", "4", "1.5", "0.3", "0.2", "0.3", "1.5", "4", "29"));
		m.put("low_12", bd("10", "3", "1.6", "1.4", "1.1", "1", "0.5", "1", "1.1", "1.4", "1.6", "3", "10"));
		m.put("medium_12", bd("33", "11", "4", "2", "1.1", "0.6", "0.3", "0.6", "1.1", "2", "4", "11", "33"));
		m.put("high_12", bd("170", "24", "8.1", "2", "0.7", "0.2", "0.2", "0.2", "0.7", "2", "8.1", "24", "170"));
		m.put("low_16", bd("16", "9", "2", "1.4", "1.4", "1.2", "1.1", "1", "0.5", "1", "1.1", "1.2", "1.4", "1.4", "2", "9", "16"));
		m.put("medium_16", bd("110", "41", "10", "5", "3", "1.5", "1", "0.5", "0.3", "0.5", "1", "1.5", "3", "5", "10", "41", "110"));
		m.put("high_16", bd("1000", "130", "26", "9", "4", "2", "0.2", "0.2", "0.2", "0.2", "0.2", "2", "4", "9", "26", "130", "1000"));
		return m;
	}

	private static List<BigDecimal> bd(String... v) {
		List<BigDecimal> l = new ArrayList<>();
		for (String s : v) l.add(new BigDecimal(s));
		return l;
	}
}
