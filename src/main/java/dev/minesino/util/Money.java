package dev.minesino.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Exact money arithmetic. All amounts are stored as {@code long} minor units ("cents"):
 * {@code 1 MS == 100} units. Floating point is never used for financial math.
 * The display precision (0..2 decimals) and symbol are presentation-only settings.
 */
public final class Money {
	/** Minor units per one whole currency unit. Fixed so stored balances never change meaning. */
	public static final long SCALE = 100L;
	/** Hard ceiling for any single balance, leaves headroom so sums of two balances cannot overflow. */
	public static final long MAX_AMOUNT = 1_000_000_000_000_000L; // 10^13 MS

	private Money() {}

	public static long ofWhole(long whole) {
		return Math.multiplyExact(whole, SCALE);
	}

	/** Checked addition that also enforces {@link #MAX_AMOUNT}. */
	public static long add(long a, long b) {
		long r = Math.addExact(a, b);
		if (r > MAX_AMOUNT || r < -MAX_AMOUNT) throw new ArithmeticException("amount limit exceeded");
		return r;
	}

	/** {@code amount * num / den}, rounded down, computed without intermediate overflow. */
	public static long mulDiv(long amount, long num, long den) {
		if (den <= 0) throw new IllegalArgumentException("den must be positive");
		BigDecimal r = BigDecimal.valueOf(amount).multiply(BigDecimal.valueOf(num))
			.divide(BigDecimal.valueOf(den), 0, RoundingMode.FLOOR);
		return r.longValueExact();
	}

	/** Formats minor units, e.g. {@code 125000 -> "1 250 MS"} (decimals=0) or {@code "1 250.00 MS"}. */
	public static String format(long amount, int decimals, String symbol) {
		return formatNumber(amount, decimals) + (symbol == null || symbol.isEmpty() ? "" : " " + symbol);
	}

	public static String formatNumber(long amount, int decimals) {
		decimals = Math.clamp(decimals, 0, 2);
		boolean neg = amount < 0;
		long abs = Math.abs(amount);
		long whole = abs / SCALE;
		long frac = abs % SCALE;
		StringBuilder sb = new StringBuilder(groupThousands(whole));
		if (decimals > 0) {
			long shown = decimals == 1 ? frac / 10 : frac;
			String f = Long.toString(shown);
			while (f.length() < decimals) f = "0" + f;
			// Whole amounts stay clean ("1 000"), fractions are shown only as far as needed ("0.1", "12.25").
			while (f.endsWith("0")) f = f.substring(0, f.length() - 1);
			if (!f.isEmpty()) sb.append('.').append(f);
		}
		return (neg ? "-" : "") + sb;
	}

	/** Signed form used for history/result rows: {@code +200 MS} / {@code -100 MS}. */
	public static String formatSigned(long amount, int decimals, String symbol) {
		return (amount > 0 ? "+" : "") + format(amount, decimals, symbol);
	}

	/** Short form for tight HUD space: 1.2K, 3.4M, ... */
	public static String formatCompact(long amount, int decimals, String symbol) {
		long whole = Math.abs(amount) / SCALE;
		String sign = amount < 0 ? "-" : "";
		String suffix;
		double v;
		if (whole >= 1_000_000_000_000L) { v = whole / 1e12; suffix = "T"; }
		else if (whole >= 1_000_000_000L) { v = whole / 1e9; suffix = "B"; }
		else if (whole >= 1_000_000L) { v = whole / 1e6; suffix = "M"; }
		else if (whole >= 10_000L) { v = whole / 1e3; suffix = "K"; }
		else return format(amount, decimals, symbol);
		// display only, never fed back into arithmetic
		String num = v >= 100 ? String.format(java.util.Locale.ROOT, "%.0f", Math.floor(v)) : String.format(java.util.Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
		return sign + num + suffix + (symbol == null || symbol.isEmpty() ? "" : " " + symbol);
	}

	private static String groupThousands(long v) {
		String s = Long.toString(v);
		StringBuilder out = new StringBuilder();
		int len = s.length();
		for (int i = 0; i < len; i++) {
			if (i > 0 && (len - i) % 3 == 0) out.append(' ');
			out.append(s.charAt(i));
		}
		return out.toString();
	}

	/**
	 * Parses user input such as {@code "1250"}, {@code "1 250.5"}, {@code "12,75"} into minor units.
	 * Returns -1 on invalid input. More than two fractional digits are rejected.
	 */
	public static long parse(String text) {
		if (text == null) return -1;
		String s = text.trim().replace(" ", "").replace(" ", "").replace("_", "").replace(',', '.');
		if (s.isEmpty() || s.length() > 24) return -1;
		try {
			BigDecimal bd = new BigDecimal(s);
			if (bd.signum() < 0 || bd.scale() > 2) return -1;
			BigDecimal units = bd.multiply(BigDecimal.valueOf(SCALE));
			long v = units.longValueExact();
			return v > MAX_AMOUNT ? -1 : v;
		} catch (NumberFormatException | ArithmeticException e) {
			return -1;
		}
	}
}
