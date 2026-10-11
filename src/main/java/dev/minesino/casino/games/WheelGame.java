package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Wheel of fortune with 30 equal segments. The server picks a segment; the stake is multiplied by
 * the segment value. Expected return = average segment multiplier = 28.8 / 30 = 96%.
 */
public final class WheelGame implements CasinoGame {
	public static final String ID = "wheel";
	/** Segment multipliers in hundredths, in wheel order. */
	public static final long[] SEGMENTS = {
		0, 150, 0, 120, 0, 200, 0, 150, 0, 300, 0, 120, 0, 150, 0,
		500, 0, 150, 0, 200, 120, 0, 150, 0, 200, 0, 120, 100, 0, 150};

	@Override
	public String id() { return ID; }

	public static long payout(long bet, int segment) {
		return BigInteger.valueOf(bet).multiply(BigInteger.valueOf(SEGMENTS[segment])).divide(BigInteger.valueOf(100)).longValueExact();
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"spin".equals(action)) throw new GameException("minesino.error.invalid_action");
		long bet = casino.readBet(player, params);
		CasinoService.Round round = casino.open(player, ID, bet);
		int seg = casino.random().nextInt(SEGMENTS.length);
		JsonObject summary = new JsonObject();
		summary.addProperty("segment", seg);
		summary.addProperty("mult", SEGMENTS[seg]);
		long paid = casino.settle(round, payout(bet, seg), summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("round", round.id());
		res.addProperty("bet", bet);
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		JsonArray a = new JsonArray();
		for (long s : SEGMENTS) a.add(s);
		o.add("segments", a);
		return o;
	}
}
