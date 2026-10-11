package dev.minesino.casino.games;

import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.Json;
import java.math.BigInteger;
import java.util.UUID;

/** Coinflip: call heads or tails; a correct call returns {@code 2 * (1 - edge)} times the stake. */
public final class CoinflipGame implements CasinoGame {
	public static final String ID = "coinflip";

	@Override
	public String id() { return ID; }

	public static long payout(long bet, long edgeBps) {
		return BigInteger.valueOf(bet).multiply(BigInteger.valueOf(2L * (10_000L - edgeBps)))
			.divide(BigInteger.valueOf(10_000L)).longValueExact();
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"flip".equals(action)) throw new GameException("minesino.error.invalid_action");
		String side = Json.getString(params, "side", "");
		if (!side.equals("heads") && !side.equals("tails")) throw new GameException("minesino.error.invalid_params");
		long edge = MineSinoConfig.bps(casino.config().casino.coinflip.houseEdgePercent);
		long bet = casino.readBet(player, params);
		CasinoService.Round round = casino.open(player, ID, bet);
		String result = casino.random().nextInt(2) == 0 ? "heads" : "tails";
		boolean win = result.equals(side);
		JsonObject summary = new JsonObject();
		summary.addProperty("result", result);
		summary.addProperty("side", side);
		long paid = casino.settle(round, win ? payout(bet, edge) : 0, summary);
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
		long edge = MineSinoConfig.bps(casino.config().casino.coinflip.houseEdgePercent);
		o.addProperty("edgeBps", edge);
		o.addProperty("multBps", 2L * (10_000L - edge));
		return o;
	}
}
