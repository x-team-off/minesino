package dev.minesino.client;

import com.google.gson.JsonObject;
import dev.minesino.economy.Json;
import dev.minesino.util.Money;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.Map;

/**
 * Client-side mirror of server state. Only for display: every value here comes from the server and
 * nothing here is ever sent back as a source of truth.
 */
public final class ClientState {
	private ClientState() {}

	/** Balance from the last server sync; meaningless until {@link #balanceLoaded}. */
	public static volatile long balance;
	public static volatile boolean balanceLoaded;
	/** Server-confirmed tablet selection. */
	public static volatile boolean tabletSelected;
	/** Local prediction until the server answers, so the slot reacts instantly. */
	public static volatile Boolean predictedSelection;
	public static long predictionTime;
	public static String symbol = "MS";
	public static int decimals = 0;
	public static boolean mobConfirm = true;
	public static boolean mobRequireTablet = true;
	/** Balance shown in animations: lags behind real balance to count up/down smoothly. */
	public static double displayedBalance;
	public static long lastBalanceChange;
	public static long lastDelta;
	/** Remembered stake per game id, in minor units. */
	public static final Map<String, Long> lastBets = new HashMap<>();
	public static final Deque<Toast> toasts = new ArrayDeque<>();

	public record Toast(String title, String body, int color, long created) {}

	public static boolean tabletActive() {
		Boolean p = predictedSelection;
		if (p != null && System.currentTimeMillis() - predictionTime < 1500) return p;
		return tabletSelected;
	}

	public static void setBalance(long b) {
		if (balanceLoaded && b != balance) {
			lastDelta = b - balance;
			lastBalanceChange = System.currentTimeMillis();
		}
		if (!balanceLoaded) displayedBalance = b;
		balance = b;
		balanceLoaded = true;
	}

	public static void applySettings(String json) {
		try {
			JsonObject o = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
			symbol = Json.getString(o, "symbol", "MS");
			decimals = Math.clamp(Json.getInt(o, "decimals", 0), 0, 2);
			mobConfirm = Json.getBool(o, "mobConfirm", true);
			mobRequireTablet = Json.getBool(o, "mobRequireTablet", true);
		} catch (RuntimeException ignored) {
			// keep previous values
		}
	}

	public static String money(long v) {
		return Money.format(v, decimals, symbol);
	}

	public static String moneySigned(long v) {
		return Money.formatSigned(v, decimals, symbol);
	}

	public static String moneyCompact(long v) {
		return Money.formatCompact(v, decimals, symbol);
	}

	public static void toast(String title, String body, int color) {
		toasts.addLast(new Toast(title, body, color, System.currentTimeMillis()));
		while (toasts.size() > 4) toasts.removeFirst();
	}

	public static void reset() {
		balanceLoaded = false;
		tabletSelected = false;
		predictedSelection = null;
		toasts.clear();
	}
}
