package dev.minesino.commands;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.suggestion.SuggestionProvider;
import dev.minesino.MineSino;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.PlayerStats;
import dev.minesino.economy.Transaction;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import dev.minesino.server.AdminAccess;
import dev.minesino.server.MineSinoServer;
import dev.minesino.server.OpLevels;
import dev.minesino.util.Money;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;
import net.minecraft.ChatFormatting;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/**
 * {@code /balance [set|add|remove <player> <amount>]} and {@code /minesino reload|stats|history|info}.
 * Balance-changing commands are visible and usable only by admins (operators at the configured level
 * or players listed in the config); every change is journaled and synced to the player if online.
 * Offline players are addressed by their last known name or UUID.
 */
public final class MineSinoCommands {
	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("dd.MM HH:mm").withZone(ZoneId.systemDefault());

	private MineSinoCommands() {}

	private static final SuggestionProvider<CommandSourceStack> PLAYERS =
		(ctx, b) -> SharedSuggestionProvider.suggest(ctx.getSource().getServer().getPlayerList().getPlayers().stream()
			.map(p -> p.getGameProfile().name()), b);

	public static boolean isAdmin(CommandSourceStack src) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) return false;
		ServerPlayer p = src.getPlayer();
		return AdminAccess.isAdmin(p == null ? null : p.getUUID(), OpLevels.of(src), ms.config().permissions);
	}

	public static void register(CommandDispatcher<CommandSourceStack> d) {
		d.register(Commands.literal("balance")
			.executes(MineSinoCommands::ownBalance)
			.then(Commands.literal("set").requires(MineSinoCommands::isAdmin)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
					.then(Commands.argument("amount", StringArgumentType.word()).executes(c -> change(c, "set")))))
			.then(Commands.literal("add").requires(MineSinoCommands::isAdmin)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
					.then(Commands.argument("amount", StringArgumentType.word()).executes(c -> change(c, "add")))))
			.then(Commands.literal("remove").requires(MineSinoCommands::isAdmin)
				.then(Commands.argument("player", StringArgumentType.word()).suggests(PLAYERS)
					.then(Commands.argument("amount", StringArgumentType.word()).executes(c -> change(c, "remove")))))
			.then(Commands.argument("player", StringArgumentType.word()).requires(MineSinoCommands::isAdmin).suggests(PLAYERS)
				.executes(MineSinoCommands::otherBalance)));

		d.register(Commands.literal("minesino")
			.then(Commands.literal("info").executes(MineSinoCommands::info))
			.then(Commands.literal("reload").requires(MineSinoCommands::isAdmin).executes(MineSinoCommands::reload))
			.then(Commands.literal("stats").executes(c -> stats(c, null))
				.then(Commands.argument("player", StringArgumentType.word()).requires(MineSinoCommands::isAdmin).suggests(PLAYERS)
					.executes(c -> stats(c, StringArgumentType.getString(c, "player")))))
			.then(Commands.literal("history").executes(c -> history(c, null, 1))
				.then(Commands.argument("player", StringArgumentType.word()).requires(MineSinoCommands::isAdmin).suggests(PLAYERS)
					.executes(c -> history(c, StringArgumentType.getString(c, "player"), 1))
					.then(Commands.argument("page", IntegerArgumentType.integer(1, 1000))
						.executes(c -> history(c, StringArgumentType.getString(c, "player"), IntegerArgumentType.getInteger(c, "page")))))));
	}

	private static MineSinoServer ms(CommandContext<CommandSourceStack> c) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) c.getSource().sendFailure(Component.translatable("minesino.cmd.not_ready"));
		return ms;
	}

	private static String fmt(MineSinoServer ms, long v) {
		return Money.format(v, ms.config().currency.displayDecimals, ms.config().currency.symbol);
	}

	/** Online name, last known name in the economy, or a literal UUID. Null when unknown. */
	private static UUID resolve(MineSinoServer ms, String name) {
		ServerPlayer online = ms.server.getPlayerList().getPlayerByName(name);
		if (online != null) return online.getUUID();
		UUID known = ms.economy.findByName(name);
		if (known != null) return known;
		try {
			UUID u = UUID.fromString(name);
			return ms.economy.hasAccount(u) ? u : null;
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static int ownBalance(CommandContext<CommandSourceStack> c) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		ServerPlayer p = c.getSource().getPlayer();
		if (p == null) {
			c.getSource().sendFailure(Component.translatable("minesino.cmd.players_only"));
			return 0;
		}
		long b = ms.economy.balance(p.getUUID());
		c.getSource().sendSuccess(() -> Component.translatable("minesino.cmd.balance_self", fmt(ms, b)).withStyle(ChatFormatting.GOLD), false);
		return 1;
	}

	private static int otherBalance(CommandContext<CommandSourceStack> c) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		String name = StringArgumentType.getString(c, "player");
		UUID id = resolve(ms, name);
		if (id == null) {
			c.getSource().sendFailure(Component.translatable("minesino.cmd.unknown_player", name));
			return 0;
		}
		long b = ms.economy.balance(id);
		c.getSource().sendSuccess(() -> Component.translatable("minesino.cmd.balance_other", name, fmt(ms, b)), false);
		return 1;
	}

	private static int change(CommandContext<CommandSourceStack> c, String mode) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		CommandSourceStack src = c.getSource();
		String name = StringArgumentType.getString(c, "player");
		String raw = StringArgumentType.getString(c, "amount");
		long amount = Money.parse(raw);
		if (amount < 0) {
			src.sendFailure(Component.translatable("minesino.cmd.bad_amount", raw));
			return 0;
		}
		if (!mode.equals("set") && amount == 0) {
			src.sendFailure(Component.translatable("minesino.cmd.bad_amount", raw));
			return 0;
		}
		UUID id = resolve(ms, name);
		if (id == null) {
			src.sendFailure(Component.translatable("minesino.cmd.unknown_player", name));
			return 0;
		}
		String actor = src.getTextName();
		String op = "admin:" + mode + ":" + id + ":" + System.nanoTime();
		TxResult r = switch (mode) {
			case "set" -> ms.economy.set(id, amount, "by " + actor, op);
			case "add" -> ms.economy.deposit(id, amount, TxType.ADMIN_ADD, "by " + actor, op);
			default -> ms.economy.withdraw(id, amount, TxType.ADMIN_REMOVE, "by " + actor, op);
		};
		if (!r.ok()) {
			String key = switch (r.status()) {
				case INSUFFICIENT_FUNDS -> "minesino.cmd.insufficient";
				case LIMIT_EXCEEDED -> "minesino.cmd.limit";
				default -> "minesino.cmd.failed";
			};
			src.sendFailure(Component.translatable(key, fmt(ms, ms.economy.balance(id))));
			return 0;
		}
		ms.journal.admin(actor, "balance " + mode + " " + name + " (" + id + ") " + amount + " -> " + r.balance());
		long nb = r.balance();
		src.sendSuccess(() -> Component.translatable("minesino.cmd.changed", name, fmt(ms, nb)).withStyle(ChatFormatting.GREEN), true);
		ServerPlayer target = ms.server.getPlayerList().getPlayer(id);
		if (target != null && target != src.getPlayer()) {
			target.sendSystemMessage(Component.translatable("minesino.cmd.notified", fmt(ms, nb)).withStyle(ChatFormatting.GOLD));
		}
		ms.saveAsync();
		return 1;
	}

	private static int reload(CommandContext<CommandSourceStack> c) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		List<String> warnings = ms.reload();
		ms.journal.admin(c.getSource().getTextName(), "config reload, " + warnings.size() + " warnings");
		c.getSource().sendSuccess(() -> Component.translatable("minesino.cmd.reloaded", warnings.size()).withStyle(ChatFormatting.GREEN), true);
		warnings.stream().limit(10).forEach(w -> c.getSource().sendSystemMessage(Component.literal(" - " + w).withStyle(ChatFormatting.YELLOW)));
		return 1;
	}

	private static UUID target(CommandContext<CommandSourceStack> c, MineSinoServer ms, String name) {
		if (name == null) {
			ServerPlayer p = c.getSource().getPlayer();
			if (p == null) c.getSource().sendFailure(Component.translatable("minesino.cmd.players_only"));
			return p == null ? null : p.getUUID();
		}
		UUID id = resolve(ms, name);
		if (id == null) c.getSource().sendFailure(Component.translatable("minesino.cmd.unknown_player", name));
		return id;
	}

	private static int stats(CommandContext<CommandSourceStack> c, String name) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		UUID id = target(c, ms, name);
		if (id == null) return 0;
		PlayerStats s = ms.economy.stats(id);
		String who = name == null ? c.getSource().getTextName() : name;
		CommandSourceStack src = c.getSource();
		src.sendSystemMessage(Component.translatable("minesino.cmd.stats_header", who).withStyle(ChatFormatting.LIGHT_PURPLE));
		src.sendSystemMessage(Component.translatable("minesino.stats.balance").append(": " + fmt(ms, ms.economy.balance(id))));
		src.sendSystemMessage(Component.translatable("minesino.stats.rounds").append(": " + s.roundsPlayed + " (" + s.wins + " / " + s.losses + " / " + s.pushes + ")"));
		src.sendSystemMessage(Component.translatable("minesino.stats.wagered").append(": " + fmt(ms, s.totalWagered)));
		src.sendSystemMessage(Component.translatable("minesino.stats.paid_out").append(": " + fmt(ms, s.totalPaidOut)));
		src.sendSystemMessage(Component.translatable("minesino.stats.net").append(": " + Money.formatSigned(s.netCasino(), ms.config().currency.displayDecimals, ms.config().currency.symbol)));
		src.sendSystemMessage(Component.translatable("minesino.stats.items_sold").append(": " + s.itemsSold + " / " + fmt(ms, s.itemSaleIncome)));
		src.sendSystemMessage(Component.translatable("minesino.stats.mobs_sold").append(": " + s.mobsSold + " / " + fmt(ms, s.mobSaleIncome)));
		src.sendSystemMessage(Component.translatable("minesino.stats.purchases").append(": " + s.purchases + " / " + fmt(ms, s.purchaseSpent)));
		return 1;
	}

	private static int history(CommandContext<CommandSourceStack> c, String name, int page) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		UUID id = target(c, ms, name);
		if (id == null) return 0;
		EconomyManager.HistoryPage h = ms.economy.history(id, TxType.Group.ALL, page - 1, 10);
		CommandSourceStack src = c.getSource();
		src.sendSystemMessage(Component.translatable("minesino.cmd.history_header", name == null ? src.getTextName() : name, h.page() + 1, h.totalPages())
			.withStyle(ChatFormatting.LIGHT_PURPLE));
		for (Transaction t : h.entries()) {
			ChatFormatting color = t.delta() > 0 ? ChatFormatting.GREEN : t.delta() < 0 ? ChatFormatting.RED : ChatFormatting.GRAY;
			src.sendSystemMessage(Component.literal(TIME.format(Instant.ofEpochMilli(t.time())) + " ")
				.append(Component.translatable("minesino.tx." + t.type().name().toLowerCase(java.util.Locale.ROOT)))
				.append(" " + t.detail() + " ")
				.append(Component.literal(Money.formatSigned(t.delta(), ms.config().currency.displayDecimals, ms.config().currency.symbol)).withStyle(color))
				.append(Component.literal(" → " + fmt(ms, t.balanceAfter())).withStyle(ChatFormatting.DARK_GRAY)));
		}
		return 1;
	}

	private static int info(CommandContext<CommandSourceStack> c) {
		MineSinoServer ms = ms(c);
		if (ms == null) return 0;
		CommandSourceStack src = c.getSource();
		String version = net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(MineSino.MOD_ID)
			.map(m -> m.getMetadata().getVersion().getFriendlyString()).orElse("?");
		src.sendSystemMessage(Component.literal("MineSino " + version).withStyle(ChatFormatting.LIGHT_PURPLE, ChatFormatting.BOLD));
		src.sendSystemMessage(Component.translatable("minesino.cmd.info_games", String.join(", ",
			ms.casino.games().all().stream().filter(g -> ms.config().gameEnabled(g.id())).map(g -> g.id()).toList())));
		src.sendSystemMessage(Component.translatable("minesino.cmd.info_prices", ms.prices().all().size(), ms.mobPrices().all().size()));
		if (isAdmin(src)) {
			src.sendSystemMessage(Component.translatable("minesino.cmd.info_economy", ms.economy.accountCount(), fmt(ms, ms.economy.totalSupply())));
		}
		return 1;
	}
}
