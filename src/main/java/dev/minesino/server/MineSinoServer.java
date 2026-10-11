package dev.minesino.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameRandom;
import dev.minesino.casino.GameRegistry;
import dev.minesino.casino.games.BlackjackGame;
import dev.minesino.casino.games.CoinflipGame;
import dev.minesino.casino.games.CrashGame;
import dev.minesino.casino.games.DiceGame;
import dev.minesino.casino.games.MinesGame;
import dev.minesino.casino.games.PlinkoGame;
import dev.minesino.casino.games.RouletteGame;
import dev.minesino.casino.games.SlotsGame;
import dev.minesino.casino.games.UpgradeGame;
import dev.minesino.config.ConfigIO;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.EconomyManager;
import dev.minesino.mobs.MobPriceRegistry;
import dev.minesino.mobs.MobSaleService;
import dev.minesino.network.MineSinoNetwork;
import dev.minesino.pricing.PriceRegistry;
import dev.minesino.pricing.RecipeEdge;
import dev.minesino.pricing.RecipeExtractor;
import dev.minesino.selling.ItemSaleService;
import dev.minesino.shop.ShopService;
import dev.minesino.storage.AsyncWriter;
import dev.minesino.storage.Journal;
import dev.minesino.storage.SafeJsonFile;
import dev.minesino.tablet.TabletState;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Server-side state of MineSino for one running server (dedicated, integrated single player or
 * LAN host). Everything that touches money lives here; clients only display.
 */
public final class MineSinoServer {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino");
	private static volatile MineSinoServer instance;

	public final MinecraftServer server;
	public final Path configDir;
	public final Path dataDir;
	private volatile MineSinoConfig config;
	private volatile PriceRegistry prices = PriceRegistry.empty();
	private volatile MobPriceRegistry mobPrices = MobPriceRegistry.empty();
	public final EconomyManager economy;
	public final CasinoService casino;
	public final TabletState tablet = new TabletState();
	public final RequestGuard guard = new RequestGuard();
	public final BalanceSyncTracker sync = new BalanceSyncTracker();
	public final AsyncWriter writer = new AsyncWriter();
	public final Journal journal;
	public final ItemSaleService selling;
	public final ShopService shop;
	public final MobSaleService mobs;
	private final SafeJsonFile economyFile;
	private long ticks;

	private MineSinoServer(MinecraftServer server) {
		this.server = server;
		this.configDir = FabricLoader.getInstance().getConfigDir().resolve("minesino");
		this.dataDir = server.getWorldPath(LevelResource.ROOT).resolve("minesino");
		this.config = loadConfig();
		this.journal = new Journal(dataDir.resolve("journal"), writer);
		journal.configure(config.logging.journal, config.logging.security);
		this.economy = new EconomyManager(config.startingBalanceUnits(), config.storage.historyLimit);
		economy.setJournal(journal::record);
		economy.addListener(sync::changed);
		GameRegistry games = new GameRegistry()
			.register(new RouletteGame())
			.register(new SlotsGame())
			.register(new BlackjackGame())
			.register(new PlinkoGame())
			.register(new DiceGame())
			.register(new UpgradeGame())
			.register(new CoinflipGame())
			.register(new MinesGame())
			.register(new CrashGame())
			.register(new dev.minesino.casino.games.WheelGame())
			.register(new dev.minesino.casino.games.HiLoGame())
			.register(new dev.minesino.casino.games.KenoGame());
		this.casino = new CasinoService(economy, () -> this.config, GameRandom.secure(), games);
		this.selling = new ItemSaleService(this);
		this.shop = new ShopService(this);
		this.mobs = new MobSaleService(this);
		casino.setItemStakes(selling.itemStakes());
		this.economyFile = new SafeJsonFile(dataDir.resolve("economy.json"), config.storage.backups);
	}

	public static MineSinoServer get() {
		return instance;
	}

	public MineSinoConfig config() { return config; }
	public PriceRegistry prices() { return prices; }
	public MobPriceRegistry mobPrices() { return mobPrices; }

	// ------------------------------------------------------------------ lifecycle

	public static void onServerStarted(MinecraftServer server) {
		MineSinoServer s = new MineSinoServer(server);
		s.loadEconomy();
		s.rebuildPrices();
		instance = s;
		LOG.info("MineSino ready: {} accounts, {} priced items, {} priced mobs",
			s.economy.accountCount(), s.prices.all().size(), s.mobPrices.all().size());
	}

	public static void onServerStopping(MinecraftServer server) {
		MineSinoServer s = instance;
		if (s == null) return;
		for (ServerPlayer p : server.getPlayerList().getPlayers()) s.casino.abandon(p.getUUID());
		s.saveNow();
		s.journal.flush();
		s.writer.close();
		instance = null;
	}

	private MineSinoConfig loadConfig() {
		List<String> warnings = new ArrayList<>();
		MineSinoConfig c = ConfigIO.load(configDir.resolve("minesino.json"), MineSinoConfig.class, new MineSinoConfig(),
			ConfigIO.mainComments(), warnings::add);
		c.validate(warnings::add);
		for (String w : warnings) LOG.warn("[config] {}", w);
		return c;
	}

	/** Reloads configuration and prices. Balances, history and active rounds are untouched. */
	public List<String> reload() {
		List<String> warnings = new ArrayList<>();
		MineSinoConfig c = ConfigIO.load(configDir.resolve("minesino.json"), MineSinoConfig.class, new MineSinoConfig(),
			ConfigIO.mainComments(), warnings::add);
		c.validate(warnings::add);
		this.config = c;
		economy.reconfigure(c.startingBalanceUnits(), c.storage.historyLimit);
		journal.configure(c.logging.journal, c.logging.security);
		warnings.addAll(rebuildPrices());
		for (String w : warnings) LOG.warn("[reload] {}", w);
		for (ServerPlayer p : server.getPlayerList().getPlayers()) sendTabletSync(p);
		return warnings;
	}

	private List<String> rebuildPrices() {
		List<String> warnings = new ArrayList<>();
		JsonElement priceFile = readOrCreate("prices.json", "/minesino/default_prices.json", warnings);
		PriceRegistry.Table table = PriceRegistry.Table.parse(priceFile.getAsJsonObject(), MineSinoServer::isKnownItem, warnings);
		List<RecipeEdge> recipes = table.deriveFromRecipes ? RecipeExtractor.extract(server.getRecipeManager()) : List.of();
		PriceRegistry p = PriceRegistry.build(table, recipes, RecipeExtractor::guessCategory);
		warnings.addAll(p.warnings());
		JsonElement mobFile = readOrCreate("mob_prices.json", "/minesino/default_mob_prices.json", warnings);
		MobPriceRegistry m = MobPriceRegistry.parse(mobFile.getAsJsonObject(), p, MineSinoServer::isKnownEntity, warnings);
		this.prices = p;
		this.mobPrices = m;
		shop.invalidate();
		for (String w : warnings) LOG.warn("[prices] {}", w);
		return warnings;
	}

	private JsonElement readOrCreate(String name, String resource, List<String> warnings) {
		Path file = configDir.resolve(name);
		try {
			Files.createDirectories(configDir);
			if (!Files.exists(file)) {
				try (InputStream in = MineSinoServer.class.getResourceAsStream(resource)) {
					Files.copy(in, file);
				}
			}
			return ConfigIO.parseLenient(Files.newBufferedReader(file, StandardCharsets.UTF_8));
		} catch (Exception e) {
			warnings.add(name + " could not be read (" + e.getMessage() + "); using built-in defaults");
			try (InputStream in = MineSinoServer.class.getResourceAsStream(resource)) {
				return ConfigIO.parseLenient(new InputStreamReader(in, StandardCharsets.UTF_8));
			} catch (Exception e2) {
				throw new IllegalStateException("Built-in price table missing", e2);
			}
		}
	}

	static boolean isKnownItem(String id) {
		Identifier rl = Identifier.tryParse(id);
		return rl != null && BuiltInRegistries.ITEM.containsKey(rl);
	}

	static boolean isKnownEntity(String id) {
		Identifier rl = Identifier.tryParse(id);
		return rl != null && BuiltInRegistries.ENTITY_TYPE.containsKey(rl);
	}

	// ------------------------------------------------------------------ persistence

	private void loadEconomy() {
		SafeJsonFile.Loaded loaded = economyFile.load();
		if (loaded.data() == null) {
			LOG.info("No economy data yet, starting fresh");
			return;
		}
		int problems = economy.loadJson(loaded.data());
		if (problems > 0) LOG.warn("Economy data loaded from {} with {} corrected problems", loaded.source(), problems);
		JsonElement pending = loaded.data().get("pending");
		int refunded = casino.recoverPending(pending != null && pending.isJsonArray() ? pending.getAsJsonArray() : new JsonArray());
		if (refunded > 0) LOG.warn("Refunded {} casino rounds left unfinished by the last shutdown", refunded);
		// Recovery itself changed balances: persist immediately so it is never repeated.
		if (refunded > 0 || loaded.recovered()) saveNow();
	}

	private String snapshot() {
		JsonObject root = economy.toJson();
		root.addProperty("version", 1);
		root.add("pending", casino.pendingToJson());
		economy.clearDirty();
		return SafeJsonFile.serialize(root);
	}

	/**
	 * Writes the economy asynchronously. Called together with world saves and when a player leaves,
	 * the same moments Minecraft persists player inventories, so items and money stay consistent.
	 */
	public void saveAsync() {
		if (!economy.isDirty()) return;
		String json = snapshot();
		writer.submit("economy", () -> economyFile.write(json));
	}

	public void saveNow() {
		String json = snapshot();
		writer.submit("economy", () -> economyFile.write(json));
		writer.flush(15_000);
	}

	// ------------------------------------------------------------------ players

	public void onJoin(ServerPlayer player) {
		economy.getOrCreate(player.getUUID(), player.getGameProfile().name());
		tablet.reset(player.getUUID());
		sendTabletSync(player);
		sync.forceSync(player.getUUID(), economy.balance(player.getUUID()));
		selling.purgeForgedTablets(player);
	}

	public void onLeave(ServerPlayer player) {
		UUID id = player.getUUID();
		casino.abandon(id); // open rounds are settled, never lost
		mobs.cancelOffers(id);
		tablet.forget(id);
		sync.forget(id);
		saveAsync();
	}

	public void sendTabletSync(ServerPlayer player) {
		JsonObject s = new JsonObject();
		s.addProperty("symbol", config.currency.symbol);
		s.addProperty("decimals", config.currency.displayDecimals);
		s.addProperty("mobConfirm", config.mobs.requireConfirmation);
		s.addProperty("mobRequireTablet", config.mobs.requireTablet);
		if (ServerPlayNetworking.canSend(player, MineSinoNetwork.TabletSync.TYPE)) {
			ServerPlayNetworking.send(player, new MineSinoNetwork.TabletSync(tablet.isSelected(player.getUUID()), s.toString()));
		}
	}

	public void tick() {
		ticks++;
		casino.tick((player, result) -> {
			ServerPlayer p = server.getPlayerList().getPlayer(player);
			if (p != null && ServerPlayNetworking.canSend(p, MineSinoNetwork.Notify.TYPE)) {
				ServerPlayNetworking.send(p, new MineSinoNetwork.Notify("game_event", result.toString()));
			}
		});
		Map<UUID, Long> balances = sync.drain();
		for (Map.Entry<UUID, Long> e : balances.entrySet()) {
			ServerPlayer p = server.getPlayerList().getPlayer(e.getKey());
			if (p != null && ServerPlayNetworking.canSend(p, MineSinoNetwork.Balance.TYPE)) {
				ServerPlayNetworking.send(p, new MineSinoNetwork.Balance(e.getValue()));
			}
		}
		if (ticks % 20 == 0) {
			mobs.expireOffers();
			for (ServerPlayer p : server.getPlayerList().getPlayers()) selling.purgeForgedTablets(p);
		}
		if (ticks % 100 == 0) journal.flush();
	}

	public long ticks() { return ticks; }

	public boolean isAdmin(ServerPlayer player) {
		return AdminAccess.isAdmin(player.getUUID(), OpLevels.of(player.createCommandSourceStack()), config.permissions);
	}
}
