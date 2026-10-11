package dev.minesino.server;

import dev.minesino.config.MineSinoConfig;
import java.util.UUID;

/**
 * Who may run balance-changing commands: operators with the configured permission level, the
 * server console, or players explicitly listed in {@code permissions.adminPlayers}.
 */
public final class AdminAccess {
	private AdminAccess() {}

	/**
	 * @param playerId null for the console / command blocks
	 * @param opLevel  effective operator level of the source (0 for regular players)
	 */
	public static boolean isAdmin(UUID playerId, int opLevel, MineSinoConfig.Permissions cfg) {
		if (opLevel >= cfg.adminLevel) return true;
		if (playerId == null) return false;
		for (String s : cfg.adminPlayers) {
			if (s != null && s.trim().equalsIgnoreCase(playerId.toString())) return true;
		}
		return false;
	}
}
