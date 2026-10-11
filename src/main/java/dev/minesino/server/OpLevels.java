package dev.minesino.server;

import net.minecraft.commands.CommandSourceStack;
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.Permissions;

/** Effective operator level (0..4) of a command source, from the 26.x permission sets. */
public final class OpLevels {
	private static final Permission[] LEVELS = {
		Permissions.COMMANDS_MODERATOR, Permissions.COMMANDS_GAMEMASTER, Permissions.COMMANDS_ADMIN, Permissions.COMMANDS_OWNER};

	private OpLevels() {}

	public static int of(CommandSourceStack source) {
		int level = 0;
		for (int i = 0; i < LEVELS.length; i++) {
			if (source.permissions().hasPermission(LEVELS[i])) level = i + 1;
		}
		return level;
	}
}
