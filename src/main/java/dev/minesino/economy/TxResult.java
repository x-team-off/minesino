package dev.minesino.economy;

/** Outcome of a balance mutation. */
public record TxResult(Status status, long balance, Transaction transaction) {
	public enum Status { OK, INSUFFICIENT_FUNDS, INVALID_AMOUNT, LIMIT_EXCEEDED, DUPLICATE }

	public boolean ok() { return status == Status.OK; }

	static TxResult fail(Status s, long balance) { return new TxResult(s, balance, null); }
}
