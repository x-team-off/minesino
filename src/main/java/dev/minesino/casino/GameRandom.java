package dev.minesino.casino;

import java.security.SecureRandom;

/** Server-side source of randomness for all casino outcomes. */
public interface GameRandom {
	int nextInt(int bound);

	long nextLong(long bound);

	static GameRandom secure() {
		SecureRandom r = new SecureRandom();
		return new GameRandom() {
			@Override
			public int nextInt(int bound) {
				return r.nextInt(bound);
			}

			@Override
			public long nextLong(long bound) {
				return r.nextLong(bound);
			}
		};
	}
}
