package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Dice: a fair six-sided die. The player picks k faces (1..5 of them: a single number or any range
 * such as 1-3 or 4-6). Win probability is k/6; the payout multiplier is
 * {@code 6/k * (1 - edge)}, i.e. the fair multiplier minus the configured house edge.
 */
public final class DiceGame implements CasinoGame {
	public static final String ID = "dice";

	@Override
	public String id() { return ID; }

	/** Total return for a winning bet (rounded down). */
	public static long payout(long bet, int k, long edgeBps) {
		return BigInteger.valueOf(bet).multiply(BigInteger.valueOf(6L * (10_000L - edgeBps)))
			.divide(BigInteger.valueOf(k * 10_000L)).longValueExact();
	}

	/** Multiplier in 1/10000 for display: 6/k * (1-edge). */
	public static long multiplierBps(int k, long edgeBps) {
		return 6L * (10_000L - edgeBps) * 10_000L / (k * 10_000L);
	}

	static boolean[] parseFaces(JsonObject params) throws GameException {
		if (!params.has("faces") || !params.get("faces").isJsonArray()) throw new GameException("minesino.error.invalid_params");
		boolean[] faces = new boolean[7];
		int k = 0;
		for (JsonElement e : params.getAsJsonArray("faces")) {
			int f;
			try {
				f = e.getAsInt();
			} catch (RuntimeException ex) {
				throw new GameException("minesino.error.invalid_params");
			}
			if (f < 1 || f > 6 || faces[f]) throw new GameException("minesino.error.invalid_params");
			faces[f] = true;
			k++;
		}
		if (k < 1 || k > 5) throw new GameException("minesino.error.invalid_params");
		return faces;
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"roll".equals(action)) throw new GameException("minesino.error.invalid_action");
		boolean[] faces = parseFaces(params);
		int k = 0;
		for (boolean b : faces) if (b) k++;
		long edge = MineSinoConfig.bps(casino.config().casino.dice.houseEdgePercent);
		long bet = casino.readBet(player, params);
		CasinoService.Round round = casino.open(player, ID, bet);
		int roll = casino.random().nextInt(6) + 1;
		boolean win = faces[roll];
		long payout = win ? payout(bet, k, edge) : 0;
		JsonObject summary = new JsonObject();
		summary.addProperty("roll", roll);
		summary.addProperty("k", k);
		long paid = casino.settle(round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("win", win);
		res.addProperty("round", round.id());
		res.addProperty("bet", bet);
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		long edge = MineSinoConfig.bps(casino.config().casino.dice.houseEdgePercent);
		o.addProperty("edgeBps", edge);
		JsonArray m = new JsonArray();
		for (int k = 1; k <= 5; k++) m.add(multiplierBps(k, edge));
		o.add("multipliers", m);
		return o;
	}
}
