package dev.minesino;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameRandom;
import dev.minesino.casino.GameRegistry;
import dev.minesino.casino.games.BlackjackGame;
import dev.minesino.casino.games.DiceGame;
import dev.minesino.casino.games.PlinkoGame;
import dev.minesino.casino.games.RouletteGame;
import dev.minesino.casino.games.SlotsGame;
import dev.minesino.casino.games.UpgradeGame;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.EconomyManager;
import dev.minesino.pricing.PriceCategory;
import dev.minesino.pricing.PriceRegistry;
import dev.minesino.pricing.RecipeEdge;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

public final class TestSupport {
	private TestSupport() {}

	/** RNG returning scripted values (each taken modulo the bound), then falling back to 0. */
	public static final class ScriptedRandom implements GameRandom {
		private final Deque<Long> values = new ArrayDeque<>();

		public ScriptedRandom(long... v) {
			for (long x : v) values.add(x);
		}

		public void push(long... v) {
			for (long x : v) values.add(x);
		}

		@Override
		public int nextInt(int bound) {
			return (int) Math.floorMod(values.isEmpty() ? 0 : values.poll(), (long) bound);
		}

		@Override
		public long nextLong(long bound) {
			return Math.floorMod(values.isEmpty() ? 0 : values.poll(), bound);
		}
	}

	public static MineSinoConfig config() {
		MineSinoConfig c = new MineSinoConfig();
		c.validate(w -> { throw new AssertionError("default config produced warning: " + w); });
		return c;
	}

	public static final java.util.concurrent.atomic.AtomicLong CLOCK = new java.util.concurrent.atomic.AtomicLong(1_000_000);

	public static CasinoService casino(EconomyManager eco, MineSinoConfig cfg, GameRandom rnd) {
		GameRegistry reg = new GameRegistry()
			.register(new RouletteGame()).register(new SlotsGame()).register(new BlackjackGame())
			.register(new PlinkoGame()).register(new DiceGame()).register(new UpgradeGame())
			.register(new dev.minesino.casino.games.CoinflipGame()).register(new dev.minesino.casino.games.MinesGame())
			.register(new dev.minesino.casino.games.CrashGame()).register(new dev.minesino.casino.games.WheelGame())
			.register(new dev.minesino.casino.games.HiLoGame()).register(new dev.minesino.casino.games.KenoGame());
		return new CasinoService(eco, () -> cfg, rnd, reg, CLOCK::get);
	}

	public static JsonObject json(String s) {
		return JsonParser.parseString(s).getAsJsonObject();
	}

	/** Vanilla-like mini catalogue: logs, planks, slabs, iron/gold ingots, blocks and nuggets. */
	public static PriceRegistry miniCatalogue() {
		JsonObject root = json("""
			{"categories":{
			  "WOOD":{"minecraft:oak_log":4},
			  "ORES":{"minecraft:iron_ingot":10,"minecraft:gold_ingot":15},
			  "FARMING":{"minecraft:carved_pumpkin":1}
			},
			"banned":["minecraft:bedrock"]}
			""");
		List<String> warnings = new java.util.ArrayList<>();
		PriceRegistry.Table t = PriceRegistry.Table.parse(root, id -> true, warnings);
		List<RecipeEdge> recipes = List.of(
			new RecipeEdge("minecraft:oak_planks", 4, List.of(List.of("minecraft:oak_log")), "planks"),
			new RecipeEdge("minecraft:oak_slab", 6, List.of(List.of("minecraft:oak_planks"), List.of("minecraft:oak_planks"), List.of("minecraft:oak_planks")), "slab"),
			new RecipeEdge("minecraft:stick", 4, List.of(List.of("minecraft:oak_planks"), List.of("minecraft:oak_planks")), "stick"),
			new RecipeEdge("minecraft:iron_block", 1, nine("minecraft:iron_ingot"), "iron_block"),
			new RecipeEdge("minecraft:iron_ingot", 9, List.of(List.of("minecraft:iron_block")), "ingot_from_block"),
			new RecipeEdge("minecraft:iron_nugget", 9, List.of(List.of("minecraft:iron_ingot")), "nuggets"),
			new RecipeEdge("minecraft:iron_ingot", 1, nine("minecraft:iron_nugget"), "ingot_from_nuggets"),
			new RecipeEdge("minecraft:gold_block", 1, nine("minecraft:gold_ingot"), "gold_block"),
			new RecipeEdge("minecraft:iron_pickaxe", 1, List.of(List.of("minecraft:iron_ingot"), List.of("minecraft:iron_ingot"),
				List.of("minecraft:iron_ingot"), List.of("minecraft:stick"), List.of("minecraft:stick")), "pickaxe"));
		return PriceRegistry.build(t, recipes, id -> id.endsWith("_pickaxe") ? PriceCategory.TOOLS : null);
	}

	public static List<List<String>> nine(String id) {
		return java.util.Collections.nCopies(9, List.of(id));
	}
}
