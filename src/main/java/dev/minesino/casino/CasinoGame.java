package dev.minesino.casino;

import com.google.gson.JsonObject;
import java.util.UUID;
import java.util.function.BiConsumer;

/**
 * A casino game. Implementations hold only rules; money always moves through {@link CasinoService},
 * which charges the stake before the outcome is generated and pays out exactly once.
 *
 * <p>To add a game (Coinflip, Mines, Crash, ...): implement this interface, register it in
 * {@link GameRegistry}, add an entry to {@code casino.enabledGames} and a client page. Games that are
 * not registered are never shown as available.
 */
public interface CasinoGame {
	String id();

	/**
	 * Handles a client action ({@code play}, {@code hit}, ...). Parameters come from an untrusted
	 * client and must be validated. Returns the JSON result displayed by the client.
	 */
	JsonObject handle(CasinoService casino, UUID player, String action, JsonObject params) throws GameException;

	/** Static description for the client UI: payout tables, limits. */
	default JsonObject describe(CasinoService casino) {
		return new JsonObject();
	}

	/**
	 * Called when the player closes the tablet or leaves the server. Multi-step games must settle
	 * their open round here so the outcome is always applied, even without the UI.
	 * Returns the final result to notify the player with, or null when nothing was open.
	 */
	default JsonObject onAbandon(CasinoService casino, UUID player) {
		return null;
	}

	/** Called every server tick for real-time games; {@code notify} pushes a result to the player. */
	default void tick(CasinoService casino, BiConsumer<UUID, JsonObject> notify) {}
}
