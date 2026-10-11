package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * European single-zero roulette: 37 pockets (0..36). Bets lose on 0 unless placed on 0 itself
 * (no "la partage"). Total return on a winning bet is {@code stake * (payout + 1)}:
 * <pre>
 *   straight (one number)              35:1
 *   red/black, even/odd, 1-18/19-36     1:1
 *   dozen (1-12/13-24/25-36), column    2:1
 * </pre>
 * These are the standard odds; the house edge is 1/37 (≈2.70%) for every bet.
 */
public final class RouletteGame implements CasinoGame {
	public static final String ID = "roulette";
	public static final int POCKETS = 37;
	private static final int MAX_BETS = 24;
	private static final boolean[] RED = new boolean[37];

	static {
		for (int n : new int[] {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36}) RED[n] = true;
	}

	public enum BetType {
		STRAIGHT(35), RED(1), BLACK(1), EVEN(1), ODD(1), LOW(1), HIGH(1), DOZEN(2), COLUMN(2);

		public final int payout;

		BetType(int payout) { this.payout = payout; }
	}

	public record Bet(BetType type, int value, long amount) {}

	@Override
	public String id() { return ID; }

	public static boolean isRed(int n) { return n > 0 && RED[n]; }

	/** Whether {@code bet} wins when the ball lands on {@code n}. */
	public static boolean wins(BetType type, int value, int n) {
		if (n == 0) return type == BetType.STRAIGHT && value == 0;
		return switch (type) {
			case STRAIGHT -> value == n;
			case RED -> RED[n];
			case BLACK -> !RED[n];
			case EVEN -> n % 2 == 0;
			case ODD -> n % 2 == 1;
			case LOW -> n <= 18;
			case HIGH -> n >= 19;
			case DOZEN -> (n - 1) / 12 + 1 == value;
			case COLUMN -> (n - 1) % 3 + 1 == value;
		};
	}

	/** Total amount returned for a bet (stake included), 0 when it loses. */
	public static long payout(Bet bet, int n) {
		return wins(bet.type(), bet.value(), n) ? Math.multiplyExact(bet.amount(), bet.type().payout + 1L) : 0;
	}

	static List<Bet> parseBets(JsonObject params) throws GameException {
		if (!params.has("bets") || !params.get("bets").isJsonArray()) throw new GameException("minesino.error.invalid_bet");
		JsonArray arr = params.getAsJsonArray("bets");
		if (arr.isEmpty() || arr.size() > MAX_BETS) throw new GameException("minesino.error.invalid_bet");
		List<Bet> bets = new ArrayList<>();
		for (JsonElement e : arr) {
			if (!e.isJsonObject()) throw new GameException("minesino.error.invalid_bet");
			JsonObject o = e.getAsJsonObject();
			BetType type;
			try {
				type = BetType.valueOf(Json.getString(o, "type", "").toUpperCase(Locale.ROOT));
			} catch (IllegalArgumentException ex) {
				throw new GameException("minesino.error.invalid_bet");
			}
			int value = Json.getInt(o, "value", -1);
			long amount = Json.getLong(o, "amount", -1);
			if (amount <= 0) throw new GameException("minesino.error.invalid_bet");
			boolean validValue = switch (type) {
				case STRAIGHT -> value >= 0 && value <= 36;
				case DOZEN, COLUMN -> value >= 1 && value <= 3;
				default -> true;
			};
			if (!validValue) throw new GameException("minesino.error.invalid_bet");
			bets.add(new Bet(type, type == BetType.STRAIGHT || type == BetType.DOZEN || type == BetType.COLUMN ? value : 0, amount));
		}
		return bets;
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"spin".equals(action)) throw new GameException("minesino.error.invalid_action");
		List<Bet> bets = parseBets(params);
		long total = 0;
		for (Bet b : bets) {
			try {
				total = Math.addExact(total, b.amount());
			} catch (ArithmeticException e) {
				throw new GameException("minesino.error.invalid_bet");
			}
		}
		CasinoService.Round round = casino.open(player, ID, total);
		// The outcome is drawn only after the stake has been charged.
		int n = casino.random().nextInt(POCKETS);
		long payout = 0;
		JsonArray winning = new JsonArray();
		for (int i = 0; i < bets.size(); i++) {
			long p = payout(bets.get(i), n);
			if (p > 0) winning.add(i);
			payout = Math.addExact(payout, p);
		}
		JsonObject summary = new JsonObject();
		summary.addProperty("number", n);
		summary.addProperty("color", n == 0 ? "green" : isRed(n) ? "red" : "black");
		long paid = casino.settle(round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("round", round.id());
		res.addProperty("bet", total);
		res.addProperty("payout", paid);
		res.add("winningBets", winning);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		JsonObject pays = new JsonObject();
		for (BetType t : BetType.values()) pays.addProperty(t.name().toLowerCase(Locale.ROOT), t.payout);
		o.add("payouts", pays);
		o.addProperty("variant", "european");
		return o;
	}
}
