package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Keno: pick 1..10 numbers out of 40; the server draws 10 distinct numbers. The payout depends on
 * picks and hits (hypergeometric odds). Every row of the paytable returns about 95% on average.
 */
public final class KenoGame implements CasinoGame {
	public static final String ID = "keno";
	public static final int NUMBERS = 40;
	public static final int DRAWN = 10;
	/** PAYTABLE[picks-1][hits], multipliers in hundredths. */
	public static final long[][] PAYTABLE = {
		{0, 380}, {0, 168, 525}, {0, 0, 503, 2152}, {0, 0, 265, 733, 4033}, {0, 0, 0, 773, 2751, 18983},
		{0, 0, 0, 428, 1170, 5219, 44815}, {0, 0, 0, 0, 1290, 4419, 24528, 264103},
		{0, 0, 0, 0, 724, 2041, 8697, 60541, 839283}, {0, 0, 0, 0, 0, 2338, 8205, 43845, 392942, 1000000},
		{0, 0, 0, 0, 0, 1306, 3920, 17249, 118681, 1000000, 1000000}};

	@Override
	public String id() { return ID; }

	public static long payout(long bet, int picks, int hits) {
		return BigInteger.valueOf(bet).multiply(BigInteger.valueOf(PAYTABLE[picks - 1][hits])).divide(BigInteger.valueOf(100)).longValueExact();
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"draw".equals(action)) throw new GameException("minesino.error.invalid_action");
		if (!params.has("picks") || !params.get("picks").isJsonArray()) throw new GameException("minesino.error.invalid_params");
		boolean[] picked = new boolean[NUMBERS + 1];
		int picks = 0;
		for (JsonElement e : params.getAsJsonArray("picks")) {
			int n;
			try {
				n = e.getAsInt();
			} catch (RuntimeException ex) {
				throw new GameException("minesino.error.invalid_params");
			}
			if (n < 1 || n > NUMBERS || picked[n]) throw new GameException("minesino.error.invalid_params");
			picked[n] = true;
			picks++;
		}
		if (picks < 1 || picks > 10) throw new GameException("minesino.error.invalid_params");
		long bet = casino.readBet(player, params);
		CasinoService.Round round = casino.open(player, ID, bet);
		boolean[] drawn = new boolean[NUMBERS + 1];
		JsonArray draw = new JsonArray();
		int hits = 0;
		while (draw.size() < DRAWN) {
			int n = casino.random().nextInt(NUMBERS) + 1;
			if (drawn[n]) continue;
			drawn[n] = true;
			draw.add(n);
			if (picked[n]) hits++;
		}
		JsonObject summary = new JsonObject();
		summary.addProperty("picks", picks);
		summary.addProperty("hits", hits);
		summary.addProperty("mult", PAYTABLE[picks - 1][hits]);
		long paid = casino.settle(round, payout(bet, picks, hits), summary);
		JsonObject res = summary.deepCopy();
		res.add("drawn", draw);
		res.addProperty("round", round.id());
		res.addProperty("bet", bet);
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		JsonArray rows = new JsonArray();
		for (long[] row : PAYTABLE) {
			JsonArray r = new JsonArray();
			for (long v : row) r.add(v);
			rows.add(r);
		}
		o.add("paytable", rows);
		return o;
	}
}
