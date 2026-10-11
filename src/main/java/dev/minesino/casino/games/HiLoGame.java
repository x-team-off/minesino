package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import java.math.BigInteger;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hi-Lo: a card (ace = 1 .. king = 13, infinite deck) is shown; guess whether the next card is
 * "higher or same" or "lower or same". Each correct guess multiplies the win by
 * {@code (1 - edge) / p}, where p is the probability of that guess. Cash out at any time after
 * the first correct guess; closing the tablet cashes out automatically.
 */
public final class HiLoGame implements CasinoGame {
	public static final String ID = "hilo";
	public static final long EDGE_BPS = 300;
	private static final int MAX_STEPS = 30;

	private static final class Run {
		final CasinoService.Round round;
		int card;
		int steps;
		BigInteger num = BigInteger.ONE; // multiplier = num / den
		BigInteger den = BigInteger.ONE;
		final JsonArray cards = new JsonArray();

		Run(CasinoService.Round round, int card) {
			this.round = round;
			this.card = card;
			cards.add(card);
		}
	}

	private final Map<UUID, Run> active = new ConcurrentHashMap<>();

	@Override
	public String id() { return ID; }

	/** Winning outcomes out of 13 for a guess from {@code card}. */
	public static int winningRanks(int card, boolean higher) {
		return higher ? 14 - card : card;
	}

	public static long payout(long bet, BigInteger num, BigInteger den) {
		return BigInteger.valueOf(bet).multiply(num).divide(den).longValueExact();
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		switch (action) {
			case "state" -> {
				Run r = active.get(player);
				if (r == null) {
					JsonObject o = new JsonObject();
					o.addProperty("status", "idle");
					o.add("recent", casino.recent(player, ID));
					return o;
				}
				return view(casino, r, null, 0);
			}
			case "start" -> {
				if (active.containsKey(player)) throw new GameException("minesino.error.round_active");
				long bet = casino.readBet(player, params);
				CasinoService.Round round = casino.open(player, ID, bet);
				Run r = new Run(round, casino.random().nextInt(13) + 1);
				active.put(player, r);
				casino.markPending(round);
				return view(casino, r, null, 0);
			}
			case "higher", "lower" -> {
				Run r = active.get(player);
				if (r == null) throw new GameException("minesino.error.no_active_round");
				boolean higher = action.equals("higher");
				int wins = winningRanks(r.card, higher);
				if (wins >= 13) throw new GameException("minesino.error.invalid_params"); // a certain guess is not a bet
				int next = casino.random().nextInt(13) + 1;
				r.cards.add(next);
				boolean ok = higher ? next >= r.card : next <= r.card;
				r.card = next;
				if (!ok) return finish(casino, r, "lost", 0);
				r.steps++;
				r.num = r.num.multiply(BigInteger.valueOf(13L * (10_000L - EDGE_BPS)));
				r.den = r.den.multiply(BigInteger.valueOf(wins * 10_000L));
				if (r.steps >= MAX_STEPS) return finish(casino, r, "cashout", payout(r.round.bet(), r.num, r.den));
				return view(casino, r, null, 0);
			}
			case "cashout" -> {
				Run r = active.get(player);
				if (r == null) throw new GameException("minesino.error.no_active_round");
				if (r.steps == 0) throw new GameException("minesino.error.reveal_first");
				return finish(casino, r, "cashout", payout(r.round.bet(), r.num, r.den));
			}
			default -> throw new GameException("minesino.error.invalid_action");
		}
	}

	private JsonObject finish(CasinoService casino, Run r, String result, long payout) {
		if (!active.remove(r.round.player(), r)) return view(casino, r, null, 0);
		JsonObject summary = new JsonObject();
		summary.addProperty("result", result);
		summary.addProperty("steps", r.steps);
		long paid = casino.settle(r.round, payout, summary);
		return view(casino, r, result, paid);
	}

	private JsonObject view(CasinoService casino, Run r, String result, long paid) {
		JsonObject o = new JsonObject();
		o.addProperty("status", result == null ? "active" : "done");
		o.addProperty("round", r.round.id());
		o.addProperty("bet", r.round.bet());
		o.addProperty("card", r.card);
		o.add("cards", r.cards.deepCopy());
		o.addProperty("steps", r.steps);
		o.addProperty("mult", payout(100, r.num, r.den));
		o.addProperty("cashout", r.steps == 0 ? 0 : payout(r.round.bet(), r.num, r.den));
		int hi = winningRanks(r.card, true), lo = winningRanks(r.card, false);
		o.addProperty("higherChance", hi);
		o.addProperty("lowerChance", lo);
		o.addProperty("higherMult", hi >= 13 ? 0 : 13L * (10_000L - EDGE_BPS) / hi / 100);
		o.addProperty("lowerMult", lo >= 13 ? 0 : 13L * (10_000L - EDGE_BPS) / lo / 100);
		if (result != null) {
			o.addProperty("result", result);
			o.addProperty("payout", paid);
			o.add("recent", casino.recent(r.round.player(), ID));
		}
		return o;
	}

	@Override
	public JsonObject onAbandon(CasinoService casino, UUID player) {
		Run r = active.get(player);
		if (r == null) return null;
		return finish(casino, r, "cashout", r.steps == 0 ? r.round.bet() : payout(r.round.bet(), r.num, r.den));
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		o.addProperty("edgeBps", EDGE_BPS);
		return o;
	}
}
