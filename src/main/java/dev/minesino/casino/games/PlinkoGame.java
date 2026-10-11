package dev.minesino.casino.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.Json;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Plinko: the ball bounces left or right with equal probability at each of {@code rows} pegs and lands
 * in bin {@code k} = number of right bounces (binomial distribution). The server generates the full
 * path; the client animation replays exactly that path. Risk level and row count select the
 * multiplier table.
 */
public final class PlinkoGame implements CasinoGame {
	public static final String ID = "plinko";
	public static final int[] ROWS = {8, 12, 16};
	public static final String[] RISKS = {"low", "medium", "high"};

	@Override
	public String id() { return ID; }

	public static long[] multipliers(MineSinoConfig.Plinko cfg, String risk, int rows) throws GameException {
		List<BigDecimal> list = cfg.multipliers.get(risk + "_" + rows);
		if (list == null || list.size() != rows + 1) throw new GameException("minesino.error.invalid_params");
		long[] m = new long[list.size()];
		for (int i = 0; i < m.length; i++) m[i] = MineSinoConfig.hundredths(list.get(i));
		return m;
	}

	/** Expected return for a table: sum C(rows,k) * m_k / 2^rows. */
	public static BigDecimal rtp(long[] multHundredths) {
		int rows = multHundredths.length - 1;
		BigInteger total = BigInteger.ZERO;
		for (int k = 0; k <= rows; k++) {
			total = total.add(binom(rows, k).multiply(BigInteger.valueOf(multHundredths[k])));
		}
		return new BigDecimal(total).divide(new BigDecimal(BigInteger.TWO.pow(rows).multiply(BigInteger.valueOf(100))), 6, RoundingMode.HALF_UP);
	}

	static BigInteger binom(int n, int k) {
		BigInteger r = BigInteger.ONE;
		for (int i = 1; i <= k; i++) r = r.multiply(BigInteger.valueOf(n - k + i)).divide(BigInteger.valueOf(i));
		return r;
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"drop".equals(action)) throw new GameException("minesino.error.invalid_action");
		String risk = Json.getString(params, "risk", "");
		int rows = Json.getInt(params, "rows", -1);
		if (!List.of(RISKS).contains(risk) || (rows != 8 && rows != 12 && rows != 16)) throw new GameException("minesino.error.invalid_params");
		long[] mult = multipliers(casino.config().casino.plinko, risk, rows);
		long bet = casino.readBet(player, params);
		CasinoService.Round round = casino.open(player, ID, bet);
		JsonArray path = new JsonArray();
		int bin = 0;
		for (int r = 0; r < rows; r++) {
			int dir = casino.random().nextInt(2);
			path.add(dir);
			bin += dir;
		}
		long payout = BigInteger.valueOf(bet).multiply(BigInteger.valueOf(mult[bin])).divide(BigInteger.valueOf(100)).longValueExact();
		JsonObject summary = new JsonObject();
		summary.addProperty("bin", bin);
		summary.addProperty("mult", mult[bin]);
		summary.addProperty("rows", rows);
		summary.addProperty("risk", risk);
		long paid = casino.settle(round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.add("path", path);
		res.addProperty("round", round.id());
		res.addProperty("bet", bet);
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		JsonObject o = new JsonObject();
		JsonObject tables = new JsonObject();
		for (Map.Entry<String, List<BigDecimal>> e : casino.config().casino.plinko.multipliers.entrySet()) {
			JsonArray a = new JsonArray();
			e.getValue().forEach(v -> a.add(MineSinoConfig.hundredths(v)));
			tables.add(e.getKey(), a);
		}
		o.add("tables", tables);
		return o;
	}
}
