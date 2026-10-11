package dev.minesino.pricing;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;

/**
 * Exact non-negative fraction of minor currency units. Derived prices (e.g. a nugget = ingot/9)
 * stay exact, so 9 nuggets are worth exactly one ingot; rounding happens only when an actual
 * quantity is converted to money, and always in the house's favour.
 */
public record Rational(BigInteger num, BigInteger den) implements Comparable<Rational> {
	public static final Rational ZERO = new Rational(BigInteger.ZERO, BigInteger.ONE);

	public Rational {
		if (den.signum() <= 0) throw new IllegalArgumentException("denominator must be positive");
		BigInteger g = num.gcd(den);
		if (g.signum() > 0 && !g.equals(BigInteger.ONE)) {
			num = num.divide(g);
			den = den.divide(g);
		}
	}

	public static Rational of(long units) {
		return new Rational(BigInteger.valueOf(units), BigInteger.ONE);
	}

	public static Rational of(long num, long den) {
		return new Rational(BigInteger.valueOf(num), BigInteger.valueOf(den));
	}

	/** From a whole-currency decimal such as {@code 0.5} (= 50 minor units). */
	public static Rational ofWhole(BigDecimal whole) {
		BigDecimal units = whole.multiply(BigDecimal.valueOf(dev.minesino.util.Money.SCALE));
		BigDecimal stripped = units.stripTrailingZeros();
		if (stripped.scale() <= 0) return new Rational(stripped.toBigIntegerExact(), BigInteger.ONE);
		return new Rational(stripped.unscaledValue(), BigInteger.TEN.pow(stripped.scale()));
	}

	public Rational add(Rational o) {
		return new Rational(num.multiply(o.den).add(o.num.multiply(den)), den.multiply(o.den));
	}

	public Rational multiply(long k) {
		return new Rational(num.multiply(BigInteger.valueOf(k)), den);
	}

	public Rational multiply(Rational o) {
		return new Rational(num.multiply(o.num), den.multiply(o.den));
	}

	public Rational multiply(BigDecimal factor) {
		return multiply(fromDecimal(factor));
	}

	public Rational divide(long k) {
		return new Rational(num, den.multiply(BigInteger.valueOf(k)));
	}

	public static Rational fromDecimal(BigDecimal d) {
		BigDecimal s = d.stripTrailingZeros();
		if (s.scale() <= 0) return new Rational(s.toBigIntegerExact(), BigInteger.ONE);
		return new Rational(s.unscaledValue(), BigInteger.TEN.pow(s.scale()));
	}

	public long floor() {
		return new BigDecimal(num).divide(new BigDecimal(den), 0, RoundingMode.FLOOR).longValueExact();
	}

	public long ceil() {
		return new BigDecimal(num).divide(new BigDecimal(den), 0, RoundingMode.CEILING).longValueExact();
	}

	public boolean isZero() {
		return num.signum() == 0;
	}

	@Override
	public int compareTo(Rational o) {
		return num.multiply(o.den).compareTo(o.num.multiply(den));
	}

	public static Rational min(Rational a, Rational b) {
		if (a == null) return b;
		if (b == null) return a;
		return a.compareTo(b) <= 0 ? a : b;
	}

	@Override
	public String toString() {
		return den.equals(BigInteger.ONE) ? num.toString() : num + "/" + den;
	}
}
