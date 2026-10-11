package dev.minesino.economy;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.UUID;

/** Mutable per-player account. Only {@link EconomyManager} mutates it, always under its lock. */
public final class Account {
	final UUID id;
	String lastKnownName;
	long balance;
	final PlayerStats stats = new PlayerStats();
	/** Most recent entries first. Bounded by config. */
	final Deque<Transaction> history = new ArrayDeque<>();

	Account(UUID id, String name, long balance) {
		this.id = id;
		this.lastKnownName = name;
		this.balance = balance;
	}

	public UUID id() { return id; }
	public String lastKnownName() { return lastKnownName; }
	public long balance() { return balance; }
	public PlayerStats stats() { return stats; }
	public Deque<Transaction> history() { return history; }
}
