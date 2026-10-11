package dev.minesino.casino.games;

import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.Json;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;

/**
 * Crash: after launch the multiplier grows in real time; the player may cash out at any moment
 * before the rocket explodes. The crash point is drawn by the server at launch,
 * {@code C = (1 - edge) / (1 - U)}, so cashing out at multiplier m wins with probability
 * {@code (1 - edge) / m}. The crash point is never sent to the client before the round ends, and the
 * cash-out multiplier is computed from the server clock, not from anything the client sends.
 * An optional auto cash-out target settles the round automatically when reached.
 *
 * <p>The multiplier curve is integer-only: +1.5% every 100 ms (about ×2 after 4.7 s, ×10 after 15.5 s).
 */
public final class CrashGame implements CasinoGame {
	public static final String ID = "crash";
	static final long SPACE = 100_000_000L;
	public static final long MAX_CRASH = 1_000_000L; // 10 000x in hundredths
	public static final long STEP_MS = 100;

	private static final class Flight {
		final CasinoService.Round round;
		final long crash;
		final long auto;
		final long start;

		Flight(CasinoService.Round round, long crash, long auto, long start) {
			this.round = round;
			this.crash = crash;
			this.auto = auto;
			this.start = start;
		}
	}

	private final Map<UUID, Flight> active = new ConcurrentHashMap<>();

	@Override
	public String id() { return ID; }

	/** Crash point in hundredths for a uniform draw {@code r} in [0, SPACE). */
	public static long crashPoint(long r, long edgeBps) {
		long c = BigInteger.valueOf(10_000L - edgeBps).multiply(BigInteger.valueOf(SPACE))
			.divide(BigInteger.valueOf(100L * (SPACE - r))).longValueExact();
		return Math.clamp(c, 100L, MAX_CRASH);
	}

	/** Multiplier in hundredths after {@code elapsedMs}; shared by server (authoritative) and client (display). */
	public static long multiplierAt(long elapsedMs) {
		long steps = Math.max(0, elapsedMs) / STEP_MS;
		long micro = 1_000_000L;
		for (long i = 0; i < steps && micro < MAX_CRASH * 10_000L; i++) micro = micro * 1015 / 1000;
		return Math.min(micro / 10_000L, MAX_CRASH);
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		MineSinoConfig.Crash cfg = casino.config().casino.crash;
		switch (action) {
			case "launch" -> {
				if (active.containsKey(player)) throw new GameException("minesino.error.round_active");
				long auto = Json.getLong(params, "auto", 0);
				if (auto != 0 && (auto < MineSinoConfig.hundredths(cfg.minTarget) || auto > MineSinoConfig.hundredths(cfg.maxTarget))) {
					throw new GameException("minesino.error.invalid_multiplier");
				}
				long edge = MineSinoConfig.bps(cfg.houseEdgePercent);
				long bet = casino.readBet(player, params);
				CasinoService.Round round = casino.open(player, ID, bet);
				// Drawn after the stake is charged; kept on the server until the round ends.
				long crash = crashPoint(casino.random().nextLong(SPACE), edge);
				Flight f = new Flight(round, crash, auto, casino.now());
				active.put(player, f);
				casino.markPending(round);
				JsonObject o = flying(f, casino);
				// A 1.00x crash ends immediately.
				JsonObject ended = advance(casino, f);
				return ended != null ? ended : o;
			}
			case "cashout" -> {
				Flight f = active.get(player);
				if (f == null) throw new GameException("minesino.error.no_active_round");
				long m = multiplierAt(casino.now() - f.start);
				if (m >= f.crash) return finish(casino, f, false, f.crash);
				return finish(casino, f, true, m);
			}
			case "state" -> {
				Flight f = active.get(player);
				if (f == null) {
					JsonObject o = new JsonObject();
					o.addProperty("status", "idle");
					o.add("recent", casino.recent(player, ID));
					return o;
				}
				JsonObject ended = advance(casino, f);
				return ended != null ? ended : flying(f, casino);
			}
			default -> throw new GameException("minesino.error.invalid_action");
		}
	}

	private JsonObject flying(Flight f, CasinoService casino) {
		JsonObject o = new JsonObject();
		o.addProperty("status", "flying");
		o.addProperty("round", f.round.id());
		o.addProperty("bet", f.round.bet());
		o.addProperty("elapsed", casino.now() - f.start);
		o.addProperty("auto", f.auto);
		return o;
	}

	/** Settles the flight if it crashed or reached the auto target; null while it is still flying. */
	private JsonObject advance(CasinoService casino, Flight f) {
		long m = multiplierAt(casino.now() - f.start);
		if (f.auto > 0 && f.auto < f.crash && m >= f.auto) return finish(casino, f, true, f.auto);
		if (m >= f.crash) return finish(casino, f, false, f.crash);
		return null;
	}

	private JsonObject finish(CasinoService casino, Flight f, boolean cashed, long at) {
		if (!active.remove(f.round.player(), f)) return null;
		long payout = cashed ? UpgradeGame.reward(f.round.bet(), at) : 0;
		JsonObject summary = new JsonObject();
		summary.addProperty("crash", f.crash);
		summary.addProperty("cashedAt", cashed ? at : 0);
		long paid = casino.settle(f.round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("status", "done");
		res.addProperty("win", cashed);
		res.addProperty("round", f.round.id());
		res.addProperty("bet", f.round.bet());
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(f.round.player(), ID));
		return res;
	}

	/** Server tick: explode crashed rockets and apply auto cash-outs, notifying the players. */
	@Override
	public void tick(CasinoService casino, BiConsumer<UUID, JsonObject> notify) {
		List<Flight> flights = new ArrayList<>(active.values());
		for (Flight f : flights) {
			JsonObject ended = advance(casino, f);
			if (ended != null) notify.accept(f.round.player(), ended);
		}
	}

	/** Closing the tablet or leaving cashes out at the current multiplier if the rocket is still flying. */
	@Override
	public JsonObject onAbandon(CasinoService casino, UUID player) {
		Flight f = active.get(player);
		if (f == null) return null;
		long m = multiplierAt(casino.now() - f.start);
		return m >= f.crash ? finish(casino, f, false, f.crash) : finish(casino, f, true, m);
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		MineSinoConfig.Crash cfg = casino.config().casino.crash;
		JsonObject o = new JsonObject();
		o.addProperty("edgeBps", MineSinoConfig.bps(cfg.houseEdgePercent));
		o.addProperty("minTarget", MineSinoConfig.hundredths(cfg.minTarget));
		o.addProperty("maxTarget", MineSinoConfig.hundredths(cfg.maxTarget));
		o.addProperty("stepMs", STEP_MS);
		return o;
	}
}
