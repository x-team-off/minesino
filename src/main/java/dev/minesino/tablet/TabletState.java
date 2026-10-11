package dev.minesino.tablet;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Server-authoritative model of the tenth hotbar slot.
 *
 * <p>The tablet is a <b>virtual</b> slot: it is not an {@code ItemStack} in the player's inventory,
 * any container, or the world. The only state is whether the slot is selected. Consequently it can
 * never be dropped, moved, put in containers or hoppers, traded, staked, duplicated or lost on
 * death/relog, and it is "present" for every player by construction. Any real
 * {@code minesino:tablet} item stack that shows up somewhere is a forgery and is deleted.
 */
public final class TabletState {
	private final Map<UUID, Boolean> selected = new HashMap<>();

	/** @return true when the selection actually changed */
	public synchronized boolean select(UUID player, boolean value) {
		Boolean prev = selected.put(player, value);
		return prev == null ? value : prev != value;
	}

	public synchronized boolean isSelected(UUID player) {
		return selected.getOrDefault(player, false);
	}

	/** On (re)join the slot always exists; selection starts on the normal hotbar. */
	public synchronized void reset(UUID player) {
		selected.put(player, false);
	}

	public synchronized void forget(UUID player) {
		selected.remove(player);
	}

	/** Number of tablets a player owns: always exactly one. */
	public int tabletCount(UUID player) {
		return 1;
	}
}
