package dev.minesino.casino;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.Json;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Shared round lifecycle for every game:
 * <ol>
 *   <li>{@link #open} validates the stake and charges it (operation {@code <round>:bet});</li>
 *   <li>the game computes the outcome with the server RNG;</li>
 *   <li>{@link #settle} pays out at most once (operation {@code <round>:pay}) and records stats.</li>
 * </ol>
 * Rounds spanning several requests (blackjack) are kept as "pending" and persisted; after a crash
 * they are refunded exactly once (operation {@code <round>:refund}).
 */
public final class CasinoService {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Casino");
	private static final int RECENT_LIMIT = 12;

	private final EconomyManager economy;
	private final Supplier<MineSinoConfig> config;
	private final GameRandom random;
	private final GameRegistry games;
	private final AtomicLong roundSeq = new AtomicLong(System.currentTimeMillis() * 1000L);
	private final Map<String, Pending> pending = new LinkedHashMap<>();
	private final Map<UUID, Map<String, Deque<JsonObject>>> recent = new HashMap<>();
	private ItemStakes itemStakes = ItemStakes.NONE;
	private final java.util.function.LongSupplier clock;

	/** Charged stake of an open round. */
	public record Round(String id, UUID player, String game, long bet) {}

	private record Pending(String roundId, UUID player, String game, long bet, long created) {}

	/** Bridge to the player's inventory for games that accept items as a stake (Upgrade). */
	public interface ItemStakes {
		ItemStakes NONE = new ItemStakes() {
			@Override
			public long value(UUID player, int slot, String itemId, int count) throws GameException {
				throw new GameException("minesino.error.items_unavailable");
			}

			@Override
			public void take(UUID player, int slot, String itemId, int count) throws GameException {
				throw new GameException("minesino.error.items_unavailable");
			}
		};

		/** Sale value of {@code count} items in {@code slot}; validates that the stack really is there. */
		long value(UUID player, int slot, String itemId, int count) throws GameException;

		/** Removes the items. Called only right after a successful {@link #value} in the same tick. */
		void take(UUID player, int slot, String itemId, int count) throws GameException;
	}

	public CasinoService(EconomyManager economy, Supplier<MineSinoConfig> config, GameRandom random, GameRegistry games) {
		this(economy, config, random, games, System::currentTimeMillis);
	}

	public CasinoService(EconomyManager economy, Supplier<MineSinoConfig> config, GameRandom random, GameRegistry games,
						 java.util.function.LongSupplier clock) {
		this.clock = clock;
		this.economy = economy;
		this.config = config;
		this.random = random;
		this.games = games;
	}

	public void setItemStakes(ItemStakes stakes) { this.itemStakes = stakes == null ? ItemStakes.NONE : stakes; }
	public ItemStakes itemStakes() { return itemStakes; }
	public EconomyManager economy() { return economy; }
	public MineSinoConfig config() { return config.get(); }
	public GameRandom random() { return random; }
	public GameRegistry games() { return games; }
	/** Server clock in ms (authoritative for real-time games such as Crash). */
	public long now() { return clock.getAsLong(); }

	/** Advances real-time games; results that end without a request are passed to {@code notify}. */
	public void tick(java.util.function.BiConsumer<UUID, JsonObject> notify) {
		for (CasinoGame g : games.all()) {
			try {
				g.tick(this, (player, result) -> {
					result.addProperty("game", g.id());
					result.addProperty("balance", economy.balance(player));
					notify.accept(player, result);
				});
			} catch (RuntimeException e) {
				LOG.error("Tick failed for game {}", g.id(), e);
			}
		}
	}

	// ------------------------------------------------------------------ dispatch

	public JsonObject handle(UUID player, String gameId, String action, JsonObject params) throws GameException {
		CasinoGame game = games.get(gameId);
		if (game == null || !config().gameEnabled(gameId)) throw new GameException("minesino.error.game_unavailable");
		JsonObject res = game.handle(this, player, action, params == null ? new JsonObject() : params);
		res.addProperty("balance", economy.balance(player));
		return res;
	}

	/**
	 * Settles every open multi-step round of the player (tablet closed or disconnect).
	 * @return results of rounds settled now, keyed by game id (for a HUD notification)
	 */
	public Map<String, JsonObject> abandon(UUID player) {
		Map<String, JsonObject> settled = new LinkedHashMap<>();
		for (CasinoGame g : games.all()) {
			try {
				JsonObject r = g.onAbandon(this, player);
				if (r != null) {
					r.addProperty("balance", economy.balance(player));
					settled.put(g.id(), r);
				}
			} catch (RuntimeException e) {
				LOG.error("Abandon handling failed for game {}", g.id(), e);
			}
		}
		return settled;
	}

	// ------------------------------------------------------------------ stakes

	/** Reads and validates {@code params.bet} (minor units) against limits and balance. */
	public long readBet(UUID player, JsonObject params) throws GameException {
		long bet = Json.getLong(params, "bet", -1);
		checkBet(player, bet);
		return bet;
	}

	public void checkBet(UUID player, long bet) throws GameException {
		MineSinoConfig cfg = config();
		if (bet <= 0) throw new GameException("minesino.error.invalid_bet");
		if (bet < cfg.minBetUnits()) throw new GameException("minesino.error.bet_too_small");
		if (bet > cfg.maxBetUnits()) throw new GameException("minesino.error.bet_too_large");
		if (bet > economy.balance(player)) throw new GameException("minesino.error.insufficient_funds");
	}

	/** Charges the stake. The outcome must be generated only after this succeeds. */
	public Round open(UUID player, String game, long bet) throws GameException {
		checkBet(player, bet);
		String rid = "r" + Long.toString(roundSeq.incrementAndGet(), 36);
		TxResult r = economy.withdraw(player, bet, TxType.BET, game + "#" + rid, rid + ":bet");
		if (!r.ok()) {
			throw new GameException(r.status() == TxResult.Status.INSUFFICIENT_FUNDS
				? "minesino.error.insufficient_funds" : "minesino.error.transaction_failed");
		}
		economy.updateStats(player, s -> s.totalWagered += bet);
		return new Round(rid, player, game, bet);
	}

	/** Item stake variant: no money is charged, the stake value is only recorded. */
	public Round openItemStake(UUID player, String game, long value, String detail) {
		String rid = "r" + Long.toString(roundSeq.incrementAndGet(), 36);
		economy.note(player, TxType.BET, game + "#" + rid + " " + detail, "", rid + ":stake");
		economy.updateStats(player, s -> s.totalWagered += value);
		return new Round(rid, player, game, value);
	}

	/**
	 * Pays {@code payout} (0 for a loss) exactly once and records the result.
	 * @return the payout actually credited
	 */
	public long settle(Round round, long payout, JsonObject summary) {
		String outcome = payout > round.bet() ? "win" : payout == round.bet() ? "push" : "loss";
		if (payout > 0) {
			TxResult r = economy.deposit(round.player(), payout, TxType.PAYOUT, round.game() + "#" + round.id(), outcome, round.id() + ":pay");
			if (!r.ok()) {
				LOG.error("Payout {} for round {} failed: {}", payout, round.id(), r.status());
				if (r.status() != TxResult.Status.DUPLICATE) {
					// Balance limit reached: refund at least the stake so the player loses nothing unfairly.
					economy.deposit(round.player(), round.bet(), TxType.REFUND, round.game() + "#" + round.id(), "refund", round.id() + ":refund");
				}
				payout = 0;
			}
		} else {
			economy.note(round.player(), TxType.PAYOUT, round.game() + "#" + round.id(), "loss", round.id() + ":pay");
		}
		final long paid = payout;
		economy.updateStats(round.player(), s -> {
			s.roundsPlayed++;
			s.totalPaidOut += paid;
			if (paid > round.bet()) s.wins++;
			else if (paid == round.bet()) s.pushes++;
			else s.losses++;
			s.biggestWin = Math.max(s.biggestWin, paid - round.bet());
		});
		clearPending(round.id());
		JsonObject entry = summary == null ? new JsonObject() : summary.deepCopy();
		entry.addProperty("round", round.id());
		entry.addProperty("bet", round.bet());
		entry.addProperty("payout", paid);
		entry.addProperty("outcome", outcome);
		Deque<JsonObject> list = recent.computeIfAbsent(round.player(), k -> new HashMap<>())
			.computeIfAbsent(round.game(), k -> new ArrayDeque<>());
		list.addFirst(entry);
		while (list.size() > RECENT_LIMIT) list.removeLast();
		return paid;
	}

	public JsonArray recent(UUID player, String game) {
		JsonArray a = new JsonArray();
		Map<String, Deque<JsonObject>> m = recent.get(player);
		if (m != null && m.containsKey(game)) m.get(game).forEach(a::add);
		return a;
	}

	// ------------------------------------------------------------------ pending rounds

	public synchronized void markPending(Round round) {
		pending.put(round.id(), new Pending(round.id(), round.player(), round.game(), round.bet(), System.currentTimeMillis()));
	}

	public synchronized void clearPending(String roundId) {
		pending.remove(roundId);
	}

	public synchronized JsonArray pendingToJson() {
		JsonArray a = new JsonArray();
		for (Pending p : pending.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("round", p.roundId());
			o.addProperty("player", p.player().toString());
			o.addProperty("game", p.game());
			o.addProperty("bet", p.bet());
			o.addProperty("created", p.created());
			a.add(o);
		}
		return a;
	}

	/**
	 * Refunds rounds that were open when the server stopped unexpectedly. Idempotent: a round that
	 * was already paid or refunded is skipped thanks to operation ids.
	 * @return number of refunded rounds
	 */
	public synchronized int recoverPending(JsonArray saved) {
		int refunded = 0;
		if (saved == null) return 0;
		List<JsonElement> items = new ArrayList<>();
		saved.forEach(items::add);
		for (JsonElement e : items) {
			try {
				JsonObject o = e.getAsJsonObject();
				String rid = o.get("round").getAsString();
				UUID player = UUID.fromString(o.get("player").getAsString());
				long bet = o.get("bet").getAsLong();
				String game = Json.getString(o, "game", "casino");
				if (!economy.isApplied(rid + ":bet") || economy.isApplied(rid + ":pay") || economy.isApplied(rid + ":refund")) continue;
				TxResult r = economy.deposit(player, bet, TxType.REFUND, game + "#" + rid, "refund", rid + ":refund");
				if (r.ok()) {
					refunded++;
					LOG.warn("Refunded unfinished {} round {} of {} ({} units)", game, rid, player, bet);
				}
			} catch (RuntimeException ex) {
				LOG.error("Corrupt pending round entry {}", e, ex);
			}
		}
		return refunded;
	}

	/** Client-facing description of all enabled games and limits. */
	public JsonObject describe() {
		MineSinoConfig cfg = config();
		JsonObject o = new JsonObject();
		o.addProperty("minBet", cfg.minBetUnits());
		o.addProperty("maxBet", cfg.maxBetUnits());
		JsonArray quick = new JsonArray();
		cfg.casino.quickBets.forEach(b -> quick.add(MineSinoConfig.units(b)));
		o.add("quickBets", quick);
		JsonObject gs = new JsonObject();
		for (CasinoGame g : games.all()) {
			if (cfg.gameEnabled(g.id())) gs.add(g.id(), g.describe(this));
		}
		o.add("games", gs);
		return o;
	}
}
