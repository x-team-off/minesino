package dev.minesino;

import static org.junit.jupiter.api.Assertions.*;

import dev.minesino.config.MineSinoConfig;
import dev.minesino.mobs.MobPriceRegistry;
import dev.minesino.mobs.MobSaleRules;
import dev.minesino.mobs.MobSaleRules.MobFacts;
import dev.minesino.pricing.PriceCategory;
import dev.minesino.pricing.PriceRegistry;
import dev.minesino.pricing.Rational;
import dev.minesino.pricing.RecipeEdge;
import dev.minesino.selling.SalePlanner;
import dev.minesino.shop.ShopPolicy;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class PricingTest {
	private final PriceRegistry prices = TestSupport.miniCatalogue();
	private final MineSinoConfig cfg = TestSupport.config();

	@Test
	void plankAndSlabPrices() {
		assertEquals(Rational.of(100), prices.unitPrice("minecraft:oak_planks"), "1 plank = 1 MS");
		assertEquals(Rational.of(50), prices.unitPrice("minecraft:oak_slab"), "slab = half a plank");
		assertEquals(Rational.of(50), prices.unitPrice("minecraft:stick"));
	}

	@Test
	void blocksOfNineIngots() {
		assertEquals(Rational.of(9 * 1000), prices.unitPrice("minecraft:iron_block"));
		assertEquals(Rational.of(9 * 1500), prices.unitPrice("minecraft:gold_block"));
	}

	@Test
	void nuggetsStayExactAndCyclesAreStable() {
		assertEquals(Rational.of(1000, 9), prices.unitPrice("minecraft:iron_nugget"));
		assertEquals(Rational.of(1000), prices.unitPrice("minecraft:iron_ingot"), "ingot <-> block <-> nugget cycle keeps base price");
		assertEquals(1000, prices.sellValue("minecraft:iron_nugget", 9, BigDecimal.ONE));
		assertEquals(111, prices.sellValue("minecraft:iron_nugget", 1, BigDecimal.ONE), "rounding is in the house's favour");
	}

	@Test
	void derivedToolPriceAndCategory() {
		assertEquals(Rational.of(3 * 1000 + 2 * 50), prices.unitPrice("minecraft:iron_pickaxe"));
		assertEquals(PriceCategory.TOOLS, prices.category("minecraft:iron_pickaxe"));
		assertEquals(PriceCategory.WOOD, prices.category("minecraft:oak_slab"), "category inherited from raw material");
	}

	@Test
	void noRecipeCanTurnItemsIntoMoreMoney() {
		PriceRegistry.Table t = PriceRegistry.Table.parse(TestSupport.json(
			"{\"categories\":{\"STONE\":{\"minecraft:a\":1,\"minecraft:b\":10}}}"), id -> true, new ArrayList<>());
		// b is listed at 10 MS but can be crafted from one 'a' (1 MS): base price must be clamped.
		List<RecipeEdge> recipes = List.of(new RecipeEdge("minecraft:b", 1, List.of(List.of("minecraft:a")), "cheap"));
		PriceRegistry r = PriceRegistry.build(t, recipes, id -> null);
		assertEquals(Rational.of(100), r.unitPrice("minecraft:b"));
		assertFalse(r.warnings().isEmpty());
	}

	@Test
	void valueCreatingCycleLeavesItemsUnpriced() {
		PriceRegistry.Table t = PriceRegistry.Table.parse(TestSupport.json(
			"{\"categories\":{\"STONE\":{\"minecraft:seed\":1}}}"), id -> true, new ArrayList<>());
		List<RecipeEdge> recipes = List.of(
			new RecipeEdge("minecraft:x", 2, List.of(List.of("minecraft:seed")), "x"),
			new RecipeEdge("minecraft:y", 2, List.of(List.of("minecraft:x")), "y"),
			new RecipeEdge("minecraft:x", 1, List.of(List.of("minecraft:y")), "loop"));
		PriceRegistry r = PriceRegistry.build(t, recipes, id -> null);
		assertTrue(r.unitPrice("minecraft:x") == null || r.unitPrice("minecraft:y") == null);
	}

	@Test
	void minimumAndSellOnlyFallback() {
		PriceRegistry.Table t = PriceRegistry.Table.parse(TestSupport.json(
			"{\"categories\":{\"PLANTS\":{\"minecraft:short_grass\":0.05}},\"minimumPrice\":0.1,\"sellFallbackPrice\":0.1,\"banned\":[\"minecraft:bedrock\"]}"),
			id -> true, new ArrayList<>());
		PriceRegistry r = PriceRegistry.build(t, List.of(), id -> null);
		assertEquals(Rational.of(10), r.unitPrice("minecraft:short_grass"), "raised to the 0.1 MS minimum");
		assertNull(r.unitPrice("minecraft:water_bucket"), "no catalogue price -> cannot be bought");
		assertEquals(10, r.sellValue("minecraft:water_bucket", 1, BigDecimal.ONE), "but sells for the 0.1 MS fallback");
		assertEquals(0, r.sellValue("minecraft:bedrock", 1, BigDecimal.ONE), "banned items never sell");
		assertEquals(0, r.sellValue("othermod:thing", 1, BigDecimal.ONE), "modded items need explicit prices");
		assertEquals(0, r.sellValue("minecraft:cow_spawn_egg", 1, BigDecimal.ONE), "spawn eggs are not sold by fallback");
		assertEquals(-1, r.buyPrice("minecraft:water_bucket", 1, new BigDecimal("2"), null));
	}

	@Test
	void itemsWithoutRulesHaveNoPrice() {
		assertNull(prices.unitPrice("minecraft:diamond_sword"));
		assertEquals(0, prices.sellValue("minecraft:diamond_sword", 1, BigDecimal.ONE));
		assertNull(prices.unitPrice("minecraft:bedrock"));
		assertEquals(PriceCategory.FORBIDDEN, prices.category("minecraft:bedrock"));
	}

	@Test
	void defaultPriceFileParses() throws Exception {
		try (var in = getClass().getResourceAsStream("/minesino/default_prices.json")) {
			assertNotNull(in);
			var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
			List<String> warnings = new ArrayList<>();
			PriceRegistry.Table t = PriceRegistry.Table.parse(root, id -> true, warnings);
			assertTrue(warnings.isEmpty(), warnings.toString());
			assertTrue(t.base.size() > 300);
			assertEquals(Rational.of(10_000), t.base.get("minecraft:iron_ingot"));
			assertEquals(Rational.of(100_000_000), t.base.get("minecraft:dragon_egg"), "dragon egg = 1 000 000 MS");
			assertTrue(t.banned.contains("minecraft:command_block"));
		}
	}

	// ------------------------------------------------------------------ mobs

	private MobPriceRegistry mobs() {
		return MobPriceRegistry.parse(TestSupport.json("""
			{"categories":{"ANIMAL":{"minecraft:cow":4},"HOSTILE":{"minecraft:zombie":2}},
			 "formulas":{"minecraft:iron_golem":{"minecraft:iron_block":4,"minecraft:carved_pumpkin":1}},
			 "banned":["minecraft:player","minecraft:wither"]}
			"""), prices, id -> true, new ArrayList<>());
	}

	private static MobFacts facts(String type) {
		return new MobFacts(type, false, true, true, false, false, false, false, false, false, false, false, false, null, false);
	}

	@Test
	void ironGolemPriceFollowsRecipe() {
		Rational expected = prices.unitPrice("minecraft:iron_block").multiply(4).add(prices.unitPrice("minecraft:carved_pumpkin"));
		assertEquals(expected, mobs().price("minecraft:iron_golem"));
		assertEquals(Rational.of(36_100), expected, "4 * 90 MS + 1 MS");
		MobFacts built = new MobFacts("minecraft:iron_golem", false, true, true, false, false, false, false, false, false, false, false, false, true, false);
		assertEquals(36_100, MobSaleRules.evaluate(built, mobs(), cfg.mobs).price());
		MobFacts farmed = new MobFacts("minecraft:iron_golem", false, true, true, false, false, false, false, false, false, false, false, false, false, false);
		assertEquals(3_610, MobSaleRules.evaluate(farmed, mobs(), cfg.mobs).price(), "iron-farm golems are worth less");
	}

	@Test
	void playersCannotBeSold() {
		MobFacts player = new MobFacts("minecraft:player", true, false, true, false, false, false, false, false, false, false, false, false, null, false);
		assertEquals(MobSaleRules.Denial.PLAYER, MobSaleRules.evaluate(player, mobs(), cfg.mobs).denial());
	}

	@Test
	void protectedMobsCannotBeSold() {
		MobPriceRegistry m = mobs();
		assertEquals(MobSaleRules.Denial.NAMED, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, true, false, true, false, false, false, false, false, false, false, null, false), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.TAMED, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, true, false, false, true, false, false, false, false, false, false, null, false), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.LEASHED, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, true, false, false, false, true, false, false, false, false, false, null, false), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.BABY, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, true, false, false, false, false, false, false, true, false, false, null, false), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.BANNED, MobSaleRules.evaluate(facts("minecraft:wither"), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.DEAD, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, false, true, false, false, false, false, false, false, false, false, null, false), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.NO_PRICE, MobSaleRules.evaluate(facts("minecraft:unknown"), m, cfg.mobs).denial());
		assertEquals(MobSaleRules.Denial.PROTECTED, MobSaleRules.evaluate(new MobFacts("minecraft:cow", false, true, true, false, false, false, false, false, false, false, false, false, null, true), m, cfg.mobs).denial());
	}

	@Test
	void bredAnimalsSellForLess() {
		MobFacts bred = new MobFacts("minecraft:cow", false, true, true, false, false, false, false, false, false, false, false, true, null, false);
		assertEquals(100, MobSaleRules.evaluate(bred, mobs(), cfg.mobs).price());
		assertEquals(400, MobSaleRules.evaluate(facts("minecraft:cow"), mobs(), cfg.mobs).price());
	}

	@Test
	void defaultMobFileParses() throws Exception {
		try (var in = getClass().getResourceAsStream("/minesino/default_mob_prices.json")) {
			var root = com.google.gson.JsonParser.parseReader(new java.io.InputStreamReader(in)).getAsJsonObject();
			List<String> w = new ArrayList<>();
			MobPriceRegistry m = MobPriceRegistry.parse(root, prices, id -> true, w);
			assertNotNull(m.price("minecraft:iron_golem"));
			assertNull(m.price("minecraft:player"));
			assertTrue(m.isBanned("minecraft:ender_dragon"));
		}
	}

	// ------------------------------------------------------------------ shop

	@Test
	void forbiddenItemsCannotBeBought() {
		for (String id : List.of("minecraft:bedrock", "minecraft:command_block", "minecraft:barrier", "minecraft:structure_void",
			"minecraft:jigsaw", "minecraft:spawner", "minesino:tablet")) {
			assertFalse(ShopPolicy.quote(id, 1, null, prices, mobs(), cfg).ok(), id);
		}
		assertFalse(ShopPolicy.quote("othermod:gem", 1, null, prices, mobs(), cfg).ok(), "unknown mods are denied by default");
		assertFalse(ShopPolicy.quote("minecraft:diamond_sword", 1, null, prices, mobs(), cfg).ok(), "unpriced items are not sold");
	}

	@Test
	void buyingCostsMoreThanSelling() {
		ShopPolicy.Offer o = ShopPolicy.quote("minecraft:iron_block", 2, null, prices, mobs(), cfg);
		assertTrue(o.ok());
		assertEquals(2 * 9000 * 2, o.price());
		assertTrue(o.price() >= prices.sellValue("minecraft:iron_block", 2, cfg.selling.sellMultiplier));
	}

	@Test
	void spawnEggRules() {
		ShopPolicy.Offer cow = ShopPolicy.quote("minecraft:cow_spawn_egg", 1, "minecraft:cow", prices, mobs(), cfg);
		assertTrue(cow.ok());
		assertTrue(cow.price() >= 400, "egg never cheaper than the mob sells for");
		assertFalse(ShopPolicy.quote("minecraft:wither_spawn_egg", 1, "minecraft:wither", prices, mobs(), cfg).ok());
		assertFalse(ShopPolicy.quote("minecraft:ghast_spawn_egg", 1, "minecraft:ghast", prices, mobs(), cfg).ok(), "eggs not in the allow list are refused");
	}

	// ------------------------------------------------------------------ selling

	@Test
	void sellingValidatesAgainstServerInventory() {
		Map<Integer, SalePlanner.SlotContent> inv = Map.of(
			3, new SalePlanner.SlotContent(3, "minecraft:iron_ingot", 20, false),
			4, new SalePlanner.SlotContent(4, "minecraft:oak_slab", 64, false),
			5, new SalePlanner.SlotContent(5, "minecraft:iron_pickaxe", 1, true));
		SalePlanner.Plan ok = SalePlanner.plan(List.of(new SalePlanner.Line(3, "minecraft:iron_ingot", 10),
			new SalePlanner.Line(4, "minecraft:oak_slab", 64)), inv, prices, cfg);
		assertTrue(ok.ok());
		assertEquals(10 * 1000 + 64 * 50, ok.total());
		assertEquals("minesino.error.inventory_changed",
			SalePlanner.plan(List.of(new SalePlanner.Line(3, "minecraft:iron_ingot", 21)), inv, prices, cfg).error(), "cannot sell more than owned");
		assertEquals("minesino.error.inventory_changed",
			SalePlanner.plan(List.of(new SalePlanner.Line(3, "minecraft:gold_ingot", 1)), inv, prices, cfg).error(), "forged item id rejected");
		assertEquals("minesino.error.inventory_changed", SalePlanner.plan(List.of(
			new SalePlanner.Line(3, "minecraft:iron_ingot", 15), new SalePlanner.Line(3, "minecraft:iron_ingot", 15)), inv, prices, cfg).error(),
			"same slot listed twice cannot oversell");
		assertEquals("minesino.error.modified_item",
			SalePlanner.plan(List.of(new SalePlanner.Line(5, "minecraft:iron_pickaxe", 1)), inv, prices, cfg).error());
		assertEquals(2, SalePlanner.allOf(null, inv, prices, cfg).size());
	}
}
