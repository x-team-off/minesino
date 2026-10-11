package dev.minesino.casino.games;

import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoGame;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.Json;
import java.math.BigInteger;
import java.util.UUID;

/**
 * Upgrade: risk a stake (money or allowed items valued at their sale price) for {@code multiplier}
 * times its value. Success chance is transparent:
 * <pre>  P(success) = (1 - commission) / multiplier</pre>
 * so the expected return is always {@code 1 - commission} regardless of the chosen multiplier.
 * Items are taken and the result generated within one server tick, so a disconnect cannot leave
 * them half-processed; on any validation error nothing is taken.
 */
public final class UpgradeGame implements CasinoGame {
	public static final String ID = "upgrade";

	@Override
	public String id() { return ID; }

	/**
	 * Success iff {@code roll < 100 * (10000 - commissionBps)} with {@code roll} uniform in
	 * {@code [0, multHundredths * 10000)}; equivalent to the formula above without floating point.
	 */
	public static long successNumerator(long commissionBps) {
		return 100L * (10_000L - commissionBps);
	}

	public static long rollSpace(long multHundredths) {
		return multHundredths * 10_000L;
	}

	/** Chance in parts per million, for display. */
	public static long chancePpm(long multHundredths, long commissionBps) {
		return successNumerator(commissionBps) * 1_000_000L / rollSpace(multHundredths);
	}

	public static long reward(long stake, long multHundredths) {
		return BigInteger.valueOf(stake).multiply(BigInteger.valueOf(multHundredths)).divide(BigInteger.valueOf(100)).longValueExact();
	}

	@Override
	public JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException {
		if (!"upgrade".equals(action)) throw new GameException("minesino.error.invalid_action");
		MineSinoConfig.Upgrade cfg = casino.config().casino.upgrade;
		long mult = Json.getLong(params, "mult", -1);
		long min = MineSinoConfig.hundredths(cfg.minMultiplier), max = MineSinoConfig.hundredths(cfg.maxMultiplier);
		if (mult < min || mult > max) throw new GameException("minesino.error.invalid_multiplier");
		long commission = MineSinoConfig.bps(cfg.commissionPercent);
		String mode = Json.getString(params, "mode", "money");
		CasinoService.Round round;
		String stakeLabel;
		if ("items".equals(mode)) {
			if (!cfg.allowItems) throw new GameException("minesino.error.items_unavailable");
			int slot = Json.getInt(params, "slot", -1);
			String itemId = Json.getString(params, "item", "");
			int count = Json.getInt(params, "count", 0);
			if (count <= 0 || count > 64) throw new GameException("minesino.error.invalid_params");
			long value = casino.itemStakes().value(player, slot, itemId, count);
			if (value <= 0) throw new GameException("minesino.error.not_sellable");
			if (value > casino.config().maxBetUnits()) throw new GameException("minesino.error.bet_too_large");
			if (value < casino.config().minBetUnits()) throw new GameException("minesino.error.bet_too_small");
			casino.itemStakes().take(player, slot, itemId, count);
			stakeLabel = itemId + " x" + count;
			round = casino.openItemStake(player, ID, value, stakeLabel);
		} else {
			long bet = casino.readBet(player, params);
			round = casino.open(player, ID, bet);
			stakeLabel = "money";
		}
		boolean success = casino.random().nextLong(rollSpace(mult)) < successNumerator(commission);
		long payout = success ? reward(round.bet(), mult) : 0;
		JsonObject summary = new JsonObject();
		summary.addProperty("mult", mult);
		summary.addProperty("success", success);
		summary.addProperty("stake", stakeLabel);
		long paid = casino.settle(round, payout, summary);
		JsonObject res = summary.deepCopy();
		res.addProperty("chancePpm", chancePpm(mult, commission));
		res.addProperty("round", round.id());
		res.addProperty("bet", round.bet());
		res.addProperty("payout", paid);
		res.add("recent", casino.recent(player, ID));
		return res;
	}

	@Override
	public JsonObject describe(CasinoService casino) {
		MineSinoConfig.Upgrade cfg = casino.config().casino.upgrade;
		JsonObject o = new JsonObject();
		o.addProperty("commissionBps", MineSinoConfig.bps(cfg.commissionPercent));
		o.addProperty("minMult", MineSinoConfig.hundredths(cfg.minMultiplier));
		o.addProperty("maxMult", MineSinoConfig.hundredths(cfg.maxMultiplier));
		o.addProperty("allowItems", cfg.allowItems);
		return o;
	}
}
