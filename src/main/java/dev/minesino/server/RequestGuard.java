package dev.minesino.server;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.UUID;

/**
 * Per-player replay and flood protection for economic requests. Each client request carries a
 * random 64-bit id; an id seen before is rejected, so a duplicated or replayed packet can never run
 * an operation twice. Requests are also rate limited.
 */
public final class RequestGuard {
	private static final int REMEMBERED_IDS = 512;

	public enum Verdict { OK, REPLAY, TOO_FAST, INVALID }

	private static final class State {
		final LinkedHashSet<Long> seen = new LinkedHashSet<>();
		long lastTick = Long.MIN_VALUE / 2;
		long windowStartMs;
		int windowCount;
	}

	private final Map<UUID, State> states = new HashMap<>();

	public synchronized Verdict check(UUID player, long requestId, long nowTick, long nowMs, int cooldownTicks, int maxPerSecond) {
		if (requestId == 0) return Verdict.INVALID;
		State s = states.computeIfAbsent(player, k -> new State());
		if (s.seen.contains(requestId)) return Verdict.REPLAY;
		if (nowMs - s.windowStartMs >= 1000) {
			s.windowStartMs = nowMs;
			s.windowCount = 0;
		}
		if (nowTick - s.lastTick < cooldownTicks || s.windowCount >= maxPerSecond) return Verdict.TOO_FAST;
		s.lastTick = nowTick;
		s.windowCount++;
		s.seen.add(requestId);
		if (s.seen.size() > REMEMBERED_IDS) {
			var it = s.seen.iterator();
			it.next();
			it.remove();
		}
		return Verdict.OK;
	}

	public synchronized void forget(UUID player) {
		states.remove(player);
	}
}
