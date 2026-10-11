package dev.minesino.server;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Coalesces balance changes so each player receives at most one balance packet per server tick,
 * carrying the latest value, instead of one packet per transaction.
 */
public final class BalanceSyncTracker {
	private final Map<UUID, Long> pending = new LinkedHashMap<>();
	private final Map<UUID, Long> lastSent = new LinkedHashMap<>();

	public synchronized void changed(UUID player, long balance) {
		pending.put(player, balance);
	}

	/** Forces a resend, e.g. after login. */
	public synchronized void forceSync(UUID player, long balance) {
		lastSent.remove(player);
		pending.put(player, balance);
	}

	/** Returns balances to send now and clears the queue. Unchanged values are skipped. */
	public synchronized Map<UUID, Long> drain() {
		Map<UUID, Long> out = new LinkedHashMap<>();
		for (Map.Entry<UUID, Long> e : pending.entrySet()) {
			Long prev = lastSent.get(e.getKey());
			if (prev == null || prev.longValue() != e.getValue()) {
				out.put(e.getKey(), e.getValue());
				lastSent.put(e.getKey(), e.getValue());
			}
		}
		pending.clear();
		return out;
	}

	public synchronized void forget(UUID player) {
		pending.remove(player);
		lastSent.remove(player);
	}
}
