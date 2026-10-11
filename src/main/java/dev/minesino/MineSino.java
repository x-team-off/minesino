package dev.minesino;

import dev.minesino.commands.MineSinoCommands;
import dev.minesino.network.MineSinoNetwork;
import dev.minesino.server.MineSinoServer;
import dev.minesino.server.ServerPackets;
import dev.minesino.tablet.TabletItems;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Player;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Common entrypoint (runs on dedicated servers and clients). Contains no client classes.
 */
public class MineSino implements ModInitializer {
	public static final String MOD_ID = "minesino";
	public static final Logger LOG = LoggerFactory.getLogger("MineSino");

	@Override
	public void onInitialize() {
		TabletItems.register();
		MineSinoNetwork.registerPayloads();
		ServerPackets.register();
		CommandRegistrationCallback.EVENT.register((dispatcher, ctx, selection) -> MineSinoCommands.register(dispatcher));

		ServerLifecycleEvents.SERVER_STARTED.register(MineSinoServer::onServerStarted);
		ServerLifecycleEvents.SERVER_STOPPING.register(MineSinoServer::onServerStopping);
		// Economy is saved together with the world (autosave, /save-all), keeping money and inventories in step.
		ServerLifecycleEvents.AFTER_SAVE.register((server, flush, force) -> {
			MineSinoServer ms = MineSinoServer.get();
			if (ms != null) ms.saveAsync();
		});
		ServerTickEvents.END_SERVER_TICK.register(server -> {
			MineSinoServer ms = MineSinoServer.get();
			if (ms != null) ms.tick();
		});
		ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
			MineSinoServer ms = MineSinoServer.get();
			if (ms != null) ms.onJoin(handler.player);
		});
		ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
			MineSinoServer ms = MineSinoServer.get();
			if (ms != null) ms.onLeave(handler.player);
		});
		// Forged tablet items dropped into the world are deleted on sight.
		ServerEntityEvents.ENTITY_LOAD.register((entity, level) -> {
			if (entity instanceof ItemEntity item && TabletItems.isTablet(item.getItem())) entity.discard();
		});

		// While the tablet slot is selected the hand holds the tablet: the hidden hotbar item must not act.
		UseItemCallback.EVENT.register((player, level, hand) -> blockWhileTablet(player));
		UseBlockCallback.EVENT.register((player, level, hand, hit) -> blockWhileTablet(player));
		UseEntityCallback.EVENT.register((player, level, hand, entity, hit) -> blockWhileTablet(player));
		AttackEntityCallback.EVENT.register((player, level, hand, entity, hit) -> blockWhileTablet(player));
		AttackBlockCallback.EVENT.register((player, level, hand, pos, dir) -> blockWhileTablet(player));
		LOG.info("MineSino initialized");
	}

	private static InteractionResult blockWhileTablet(Player player) {
		if (player.level().isClientSide()) return InteractionResult.PASS;
		MineSinoServer ms = MineSinoServer.get();
		return ms != null && ms.tablet.isSelected(player.getUUID()) ? InteractionResult.FAIL : InteractionResult.PASS;
	}
}
