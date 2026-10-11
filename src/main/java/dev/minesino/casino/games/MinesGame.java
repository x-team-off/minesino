package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.Json;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Mines: a 5x5 field hides {@code m} mines, placed by the server when the round starts. Each safe
 * tile raises the multiplier to the fair odds of surviving that many picks, minus the house edge:
 * <pre>  mult(k) = (1 - edge) * prod_{i<k} (25 - i) / (25 - m - i)</pre>
 * The player may cash out after any safe tile. Closing the tablet or leaving cashes out
 * automatically (or returns the stake if nothing was revealed yet). Mine positions are never sent
 * to the client before the round ends.
 */
public final class MinesGame implements CasinoGame {
	public static final String ID = "mines";
	public static final int TILES = 25;

	private static final class Field {
		final CasinoService.Round round;
		final boolean[] mine = new boolean[TILES];
		final boolean[] open = new boolean[TILES];
		final int mines;
		int revealed;

		Field(CasinoService.Round round, int mines) {
			this.round = round;
			this.mines = mines;
		}
	}

	private final Map<UUID, Field> active = new ConcurrentHashMap<>();

	@Override
	public String id() { return ID; }

	/** Payout for {@code k} safe tiles, rounded down. k = 0 returns the stake. */
	public static long payout(long bet, int mines, int k, long edgeBps) {
		if (k == 0) return bet;
		BigInteger num = BigInteger.valueOf(bet).multiply(BigInteger.valueOf(10_000L - edgeBps));
		BigInteger den = BigInteger.valueOf(10_000L);
		for (int i = 0; i < k; i++) {
			num = num.multiply(BigInteger.valueOf(TILES - i));
			den = den.multiply(BigInteger.valueOf(TILES - mines - i));
		}
		return num.divide(den).longValueExact();
	}

	/** Multiplier in hundredths for display. */
	public static long multiplierHundredths(int mines, int k, long edgeBps) {
		return k == 0 ? 100 : payout(100, mines, k, edgeBps);
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		long edge = MineSinoConfig.bps(casino.config().casino.mines.houseEdgePercent);
		switch (action) {
			case "state" -> {
				Field f = active.get(player);
				if (f == null) {
					JsonObject o = new JsonObject();
					o.addProperty("status", "idle");
					o.add("recent", casino.recent(player, ID));
					return o;
				}
				return view(casino, f, edge, null, 0);
			}
			case "start" -> {
				if (active.containsKey(player)) throw new GameException("minesino.error.round_active");
				int mines = Json.getInt(params, "mines", -1);
				if (mines < 1 || mines > 24) throw new GameException("minesino.error.invalid_params");
				long bet = casino.readBet(player, params);
				CasinoService.Round round = casino.open(player, ID, bet);
				Field f = new Field(round, mines);
				int placed = 0;
				while (placed < mines) {
					int t = casino.random().nextInt(TILES);
					if (!f.mine[t]) {
						f.mine[t] = true;
						placed++;
					}
				}
				active.put(player, f);
				casino.markPending(round);
				return view(casino, f, edge, null, 0);
			}
			case "reveal" -> {
				Field f = active.get(player);
				if (f == null) throw new GameException("minesino.error.no_active_round");
				int tile = Json.getInt(params, "tile", -1);
				if (tile < 0 || tile >= TILES || f.open[tile]) throw new GameException("minesino.error.invalid_params");
				f.open[tile] = true;
				if (f.mine[tile]) return finish(casino, f, edge, "boom", 0);
				f.revealed++;
				if (f.revealed == TILES - f.mines) return finish(casino, f, edge, "cleared", payout(f.round.bet(), f.mines, f.revealed, edge));
				return view(casino, f, edge, null, 0);
			}
			case "cashout" -> {
				Field f = active.get(player);
				if (f == null) throw new GameException("minesino.error.no_active_round");
				if (f.revealed == 0) throw new GameException("minesino.error.reveal_first");
				return finish(casino, f, edge, "cashout", payout(f.round.bet(), f.mines, f.revealed, edge));
			}
			default -> throw new GameException("minesino.error.invalid_action");
		}
	}

	private JsonObject finish(CasinoService casino, Field f, long edge, String result, long payout) {
		if (!active.remove(f.round.player(), f)) return view(casino, f, edge, null, 0);
		JsonObject summary = new JsonObject();
		summary.addProperty("result", result);
		summary.addProperty("mines", f.mines);
		summary.addProperty("revealed", f.revealed);
		long paid = casino.settle(f.round, payout, summary);
		return view(casino, f, edge, result, paid);
	}

	private JsonObject view(CasinoService casino, Field f, long edge, String result, long paid) {
		JsonObject o = new JsonObject();
		boolean done = result != null;
		o.addProperty("status", done ? "done" : "active");
		o.addProperty("round", f.round.id());
		o.addProperty("bet", f.round.bet());
		o.addProperty("mines", f.mines);
		o.addProperty("revealed", f.revealed);
		JsonArray open = new JsonArray();
		JsonArray mines = new JsonArray();
		for (int i = 0; i < TILES; i++) {
			if (f.open[i]) open.add(i);
			if (done && f.mine[i]) mines.add(i);
		}
		o.add("open", open);
		if (done) o.add("minePositions", mines);
		o.addProperty("mult", multiplierHundredths(f.mines, f.revealed, edge));
		o.addProperty("nextMult", f.revealed < TILES - f.mines ? multiplierHundredths(f.mines, f.revealed + 1, edge) : 0);
		if (done) {
			o.addProperty("result", result);
			o.addProperty("payout", paid);
			o.add("recent", casino.recent(f.round.player(), ID));
		}
		return o;
	}

	/** Leaving cashes out what has been earned so far (the stake back if nothing was revealed). */
	@Override
	public JsonObject onAbandon(CasinoService casino, UUID player) {
		Field f = active.get(player);
		if (f == null) return null;
		long edge = MineSinoConfig.bps(casino.config().casino.mines.houseEdgePercent);
		return finish(casino, f, edge, "cashout", payout(f.round.bet(), f.mines, f.revealed, edge));
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		o.addProperty("edgeBps", MineSinoConfig.bps(casino.config().casino.mines.houseEdgePercent));
		return o;
	}
}
