package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.UUID;

/**
 * Three-reel, three-row slot machine with five paylines (middle, top, bottom, two diagonals).
 * Each reel is a 32-stop strip built from the configured symbol weights; the server picks one stop
 * per reel. Every line stakes {@code bet / 5}: three identical symbols pay {@code pay3}, and the
 * pair symbol on reels 1-2 pays {@code pairMultiplier}. The client receives only the final grid.
 */
public final class SlotsGame implements CasinoGame {
	public static final String ID = "slots";
	public static final int STOPS = 32;
	public static final int LINES = 5;
	/** Row index on each reel for every payline. */
	public static final int[][] LINE_ROWS = {{1, 1, 1}, {0, 0, 0}, {2, 2, 2}, {0, 1, 2}, {2, 1, 0}};

	@Override
	public String id() { return ID; }

	/** Paytable derived from configuration, multipliers in hundredths. */
	public record Paytable(List<String> symbols, long[] pay3Hundredths, int pairSymbol, long pairHundredths, int[][] strips) {}

	public static Paytable paytable(MineSinoConfig.Slots cfg) {
		List<String> ids = new ArrayList<>();
		long[] pay3 = new long[cfg.symbols.size()];
		List<Integer> base = new ArrayList<>();
		for (int i = 0; i < cfg.symbols.size(); i++) {
			MineSinoConfig.SlotSymbol s = cfg.symbols.get(i);
			ids.add(s.id);
			pay3[i] = MineSinoConfig.hundredths(s.pay3);
			for (int k = 0; k < s.weight; k++) base.add(i);
		}
		int[][] strips = new int[3][];
		for (int reel = 0; reel < 3; reel++) {
			List<Integer> strip = new ArrayList<>(base);
			// Fixed seed: strips are identical across restarts; only the stop position is random.
			Collections.shuffle(strip, new Random(0x5107L + reel * 7919L));
			strips[reel] = strip.stream().mapToInt(Integer::intValue).toArray();
		}
		int pair = ids.indexOf(cfg.pairSymbol);
		return new Paytable(ids, pay3, pair, MineSinoConfig.hundredths(cfg.pairMultiplier), strips);
	}

	/** grid[row][reel] symbol indices for the given reel stops. */
	public static int[][] grid(Paytable p, int[] stops) {
		int[][] g = new int[3][3];
		for (int reel = 0; reel < 3; reel++) {
			for (int row = 0; row < 3; row++) {
				g[row][reel] = p.strips()[reel][Math.floorMod(stops[reel] + row - 1, STOPS)];
			}
		}
		return g;
	}

	/** Line multiplier in hundredths (0 when the line does not win). */
	public static long lineWin(Paytable p, int[][] g, int line) {
		int a = g[LINE_ROWS[line][0]][0], b = g[LINE_ROWS[line][1]][1], c = g[LINE_ROWS[line][2]][2];
		if (a == b && b == c) return p.pay3Hundredths()[a];
		if (p.pairSymbol() >= 0 && a == p.pairSymbol() && b == p.pairSymbol()) return p.pairHundredths();
		return 0;
	}

	/** Total payout for a bet: {@code bet * sum(line multipliers) / LINES}, rounded down. */
	public static long payout(long bet, long sumHundredths) {
		return BigInteger.valueOf(bet).multiply(BigInteger.valueOf(sumHundredths))
			.divide(BigInteger.valueOf(LINES * 100L)).longValueExact();
	}

	/** Exact return-to-player by enumerating all 32^3 reel positions. */
	public static BigDecimal exactRtp(Paytable p) {
		long totalHundredths = 0;
		int[] stops = new int[3];
		for (stops[0] = 0; stops[0] < STOPS; stops[0]++) {
			for (stops[1] = 0; stops[1] < STOPS; stops[1]++) {
				for (stops[2] = 0; stops[2] < STOPS; stops[2]++) {
					int[][] g = grid(p, stops);
					for (int l = 0; l < LINES; l++) totalHundredths += lineWin(p, g, l);
				}
			}
		}
		long outcomes = (long) STOPS * STOPS * STOPS;
		return BigDecimal.valueOf(totalHundredths).divide(BigDecimal.valueOf(outcomes * LINES * 100L), 6, java.math.RoundingMode.HALF_UP);
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"spin".equals(action)) throw new GameException("minesino.error.invalid_action");
		long bet = casino.readBet(player, params);
		Paytable p = paytable(casino.config().casino.slots);
		CasinoService.Round round = casino.open(player, ID, bet);
		int[] stops = {casino.random().nextInt(STOPS), casino.random().nextInt(STOPS), casino.random().nextInt(STOPS)};
		int[][] g = grid(p, stops);
		long sum = 0;
		JsonArray lines = new JsonArray();
		for (int l = 0; l < LINES; l++) {
			long w = lineWin(p, g, l);
			if (w > 0) {
				JsonObject lo = new JsonObject();
				lo.addProperty("line", l);
				lo.addProperty("mult", w);
				lines.add(lo);
				sum += w;
			}
		}
		long payout = payout(bet, sum);
		JsonObject summary = new JsonObject();
		JsonArray grid = new JsonArray();
		for (int row = 0; row < 3; row++) {
			JsonArray r = new JsonArray();
			for (int reel = 0; reel < 3; reel++) r.add(p.symbols().get(g[row][reel]));
			grid.add(r);
		}
		summary.add("grid", grid);
		long paid = casino.settle(round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("round", round.id());
		res.addProperty("bet", bet);
		res.addProperty("payout", paid);
		res.add("lines", lines);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		Paytable p = paytable(casino.config().casino.slots);
		JsonObject o = new JsonObject();
		JsonArray syms = new JsonArray();
		for (int i = 0; i < p.symbols().size(); i++) {
			JsonObject s = new JsonObject();
			s.addProperty("id", p.symbols().get(i));
			s.addProperty("pay3", p.pay3Hundredths()[i]);
			syms.add(s);
		}
		o.add("symbols", syms);
		o.addProperty("pairSymbol", p.pairSymbol() >= 0 ? p.symbols().get(p.pairSymbol()) : "");
		o.addProperty("pairMult", p.pairHundredths());
		o.addProperty("lines", LINES);
		o.addProperty("rtp", exactRtp(p).toPlainString());
		return o;
	}
}
