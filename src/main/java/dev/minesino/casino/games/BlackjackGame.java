package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Blackjack against a virtual dealer, played from a freshly shuffled multi-deck shoe each round.
 * Rules: dealer stands on soft 17 (configurable), dealer peeks for blackjack, natural pays 3:2
 * (configurable), a win pays 1:1, a tie returns the stake. Actions: {@code start}, {@code hit},
 * {@code stand}, {@code state}. The whole hand lives on the server; the dealer's hole card is not
 * sent to the client until the round ends.
 */
public final class BlackjackGame implements CasinoGame {
	public static final String ID = "blackjack";

	/** Cards are ints 0..51: rank = c % 13 (0 = ace .. 12 = king), suit = c / 13. */
	static final class Hand {
		final CasinoService.Round round;
		final List<Integer> shoe;
		final List<Integer> player = new ArrayList<>();
		final List<Integer> dealer = new ArrayList<>();
		int next;

		Hand(CasinoService.Round round, List<Integer> shoe) {
			this.round = round;
			this.shoe = shoe;
		}

		int draw() {
			return shoe.get(next++);
		}
	}

	private final Map<UUID, Hand> active = new ConcurrentHashMap<>();

	@Override
	public String id() { return ID; }

	public static int cardValue(int card) {
		int rank = card % 13;
		if (rank == 0) return 1;
		return Math.min(10, rank + 1);
	}

	/** Best total counting one ace as 11 when it does not bust. */
	public static int total(List<Integer> cards) {
		int sum = 0;
		boolean ace = false;
		for (int c : cards) {
			sum += cardValue(c);
			if (c % 13 == 0) ace = true;
		}
		return ace && sum + 10 <= 21 ? sum + 10 : sum;
	}

	public static boolean isSoft(List<Integer> cards) {
		int hard = 0;
		boolean ace = false;
		for (int c : cards) {
			hard += cardValue(c);
			if (c % 13 == 0) ace = true;
		}
		return ace && hard + 10 <= 21;
	}

	public static boolean isNatural(List<Integer> cards) {
		return cards.size() == 2 && total(cards) == 21;
	}

	public static boolean dealerShouldHit(List<Integer> dealer, boolean hitSoft17) {
		int t = total(dealer);
		return t < 17 || (hitSoft17 && t == 17 && isSoft(dealer));
	}

	/** Total returned for a natural: stake + stake * num / den (rounded down). */
	public static long naturalPayout(long bet, int num, int den) {
		return bet + BigInteger.valueOf(bet).multiply(BigInteger.valueOf(num)).divide(BigInteger.valueOf(den)).longValueExact();
	}

	public enum Result { WIN, LOSS, PUSH, BLACKJACK, BUST, DEALER_BUST, DEALER_BLACKJACK }

	/** Final comparison once both hands are complete. */
	public static Result compare(List<Integer> player, List<Integer> dealer) {
		int p = total(player), d = total(dealer);
		boolean pn = isNatural(player), dn = isNatural(dealer);
		if (p > 21) return Result.BUST;
		if (pn && dn) return Result.PUSH;
		if (pn) return Result.BLACKJACK;
		if (dn) return Result.DEALER_BLACKJACK;
		if (d > 21) return Result.DEALER_BUST;
		if (p > d) return Result.WIN;
		if (p < d) return Result.LOSS;
		return Result.PUSH;
	}

	public static long payoutFor(Result r, long bet, MineSinoConfig.Blackjack cfg) {
		return switch (r) {
			case BLACKJACK -> naturalPayout(bet, cfg.blackjackPayNumerator, cfg.blackjackPayDenominator);
			case WIN, DEALER_BUST -> Math.multiplyExact(bet, 2);
			case PUSH -> bet;
			case LOSS, BUST, DEALER_BLACKJACK -> 0;
		};
	}

	List<Integer> newShoe(CasinoService casino, int decks) {
		List<Integer> shoe = new ArrayList<>(decks * 52);
		for (int d = 0; d < decks; d++) for (int c = 0; c < 52; c++) shoe.add(c);
		for (int i = shoe.size() - 1; i > 0; i--) {
			int j = casino.random().nextInt(i + 1);
			int t = shoe.get(i);
			shoe.set(i, shoe.get(j));
			shoe.set(j, t);
		}
		return shoe;
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		MineSinoConfig.Blackjack cfg = casino.config().casino.blackjack;
		switch (action) {
			case "state" -> {
				Hand h = active.get(player);
				return h == null ? idle(casino, player) : view(casino, h, null, 0);
			}
			case "start" -> {
				if (active.containsKey(player)) throw new GameException("minesino.error.round_active");
				long bet = casino.readBet(player, params);
				CasinoService.Round round = casino.open(player, ID, bet);
				Hand h = new Hand(round, newShoe(casino, cfg.decks));
				h.player.add(h.draw());
				h.dealer.add(h.draw());
				h.player.add(h.draw());
				h.dealer.add(h.draw());
				active.put(player, h);
				casino.markPending(round);
				if (isNatural(h.player) || isNatural(h.dealer)) return finish(casino, h, cfg);
				return view(casino, h, null, 0);
			}
			case "hit" -> {
				Hand h = requireActive(player);
				h.player.add(h.draw());
				if (total(h.player) >= 21) return finish(casino, h, cfg);
				return view(casino, h, null, 0);
			}
			case "stand" -> {
				return finish(casino, requireActive(player), cfg);
			}
			default -> throw new GameException("minesino.error.invalid_action");
		}
	}

	private Hand requireActive(UUID player) throws GameException {
		Hand h = active.get(player);
		if (h == null) throw new GameException("minesino.error.no_active_round");
		return h;
	}

	/** Dealer plays out and the round is settled. Removing the hand first makes it settle once. */
	private JsonObject finish(CasinoService casino, Hand h, MineSinoConfig.Blackjack cfg) {
		if (!active.remove(h.round.player(), h)) return view(casino, h, null, 0);
		boolean dealerPlays = total(h.player) <= 21 && !isNatural(h.player) && !isNatural(h.dealer);
		if (dealerPlays) {
			while (dealerShouldHit(h.dealer, cfg.dealerHitsSoft17)) h.dealer.add(h.draw());
		}
		Result r = compare(h.player, h.dealer);
		long payout = payoutFor(r, h.round.bet(), cfg);
		JsonObject summary = new JsonObject();
		summary.addProperty("result", r.name().toLowerCase(java.util.Locale.ROOT));
		summary.addProperty("player", total(h.player));
		summary.addProperty("dealer", total(h.dealer));
		long paid = casino.settle(h.round, payout, summary);
		return view(casino, h, r, paid);
	}

	private JsonObject idle(CasinoService casino, UUID player) {
		JsonObject o = new JsonObject();
		o.addProperty("status", "idle");
		o.add("recent", casino.recent(player, ID));
		return o;
	}

	private JsonObject view(CasinoService casino, Hand h, Result result, long paid) {
		JsonObject o = new JsonObject();
		boolean done = result != null;
		o.addProperty("status", done ? "done" : "active");
		o.addProperty("round", h.round.id());
		o.addProperty("bet", h.round.bet());
		JsonArray pc = new JsonArray();
		h.player.forEach(pc::add);
		o.add("playerCards", pc);
		o.addProperty("playerTotal", total(h.player));
		JsonArray dc = new JsonArray();
		if (done) h.dealer.forEach(dc::add);
		else dc.add(h.dealer.getFirst()); // hole card stays on the server
		o.add("dealerCards", dc);
		o.addProperty("dealerTotal", done ? total(h.dealer) : total(List.of(h.dealer.getFirst())));
		o.addProperty("dealerHidden", !done);
		if (done) {
			o.addProperty("result", result.name().toLowerCase(java.util.Locale.ROOT));
			o.addProperty("payout", paid);
			o.add("recent", casino.recent(h.round.player(), ID));
		}
		return o;
	}

	/** Closing the tablet or leaving stands automatically: the dealer plays out and the round is paid. */
	@Override
	public JsonObject onAbandon(CasinoService casino, UUID player) {
		Hand h = active.get(player);
		if (h == null) return null;
		return finish(casino, h, casino.config().casino.blackjack);
	}

	public boolean hasActive(UUID player) {
		return active.containsKey(player);
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		MineSinoConfig.Blackjack cfg = casino.config().casino.blackjack;
		JsonObject o = new JsonObject();
		o.addProperty("decks", cfg.decks);
		o.addProperty("hitSoft17", cfg.dealerHitsSoft17);
		o.addProperty("naturalNum", cfg.blackjackPayNumerator);
		o.addProperty("naturalDen", cfg.blackjackPayDenominator);
		return o;
	}
}
