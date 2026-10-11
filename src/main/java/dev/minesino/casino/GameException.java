package dev.minesino.casino;

/**
 * A rejected game request. {@code key} is a translation key shown to the player
 * (for example {@code minesino.error.insufficient_funds}).
 */
public final class GameException extends Exception {
	private final String key;
	private final String arg;

	public GameException(String key) {
		this(key, "");
	}

	public GameException(String key, String arg) {
		super(key + (arg.isEmpty() ? "" : " " + arg), null, false, false);
		this.key = key;
		this.arg = arg;
	}

	public String key() { return key; }
	public String arg() { return arg; }
}
