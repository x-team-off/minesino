package dev.minesino.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.mojang.blaze3d.platform.InputConstants;
import dev.minesino.MineSino;
import dev.minesino.client.gui.MineSinoScreen;
import dev.minesino.client.gui.MobSaleScreen;
import dev.minesino.client.gui.TabletData;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.hud.TabletHud;
import dev.minesino.economy.Json;
import dev.minesino.network.MineSinoNetwork;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.fabricmc.fabric.api.event.client.player.ClientHotbarScrollEvents;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.resources.Identifier;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.phys.EntityHitResult;

/** Client entrypoint: tenth slot input, HUD, packets and screens. Never loaded on dedicated servers. */
public class MineSinoClient implements ClientModInitializer {
	public static final KeyMapping.Category CATEGORY = KeyMapping.Category.register(Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "main"));
	/** Selects the tablet slot. Default: the "0" key of the top number row; rebindable in Controls. */
	public static KeyMapping tabletKey;
	private static long lastMobRequest;
	private static long lastOpenRequest;

	@Override
	public void onInitializeClient() {
		ClientSettings.load();
		// Same key code space as vanilla "key.hotbar.1".."9" (30..38): 39 is the top-row "0".
		tabletKey = KeyMappingHelper.registerKeyMapping(new KeyMapping("key.minesino.tablet_slot", InputConstants.KEY_0, CATEGORY));

		HudElementRegistry.attachElementAfter(VanillaHudElements.HOTBAR, Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "tablet_slot"),
			TabletHud::render);

		registerReceivers();

		ClientTickEvents.START_CLIENT_TICK.register(mc -> {
			// Drop / swap keys would act on the hidden hotbar item: swallow them while the tablet is held.
			if (mc.player != null && ClientState.tabletActive()) {
				while (mc.options.keyDrop.consumeClick()) {}
				while (mc.options.keySwapOffhand.consumeClick()) {}
			}
		});
		ClientTickEvents.END_CLIENT_TICK.register(mc -> {
			if (mc.player == null) return;
			while (tabletKey.consumeClick()) {
				if (!mc.player.isSpectator()) selectTablet(true);
			}
		});

		ClientHotbarScrollEvents.ALLOW.register(MineSinoClient::onScroll);

		ClientPlayConnectionEvents.JOIN.register((handler, sender, mc) -> ClientState.reset());
		ClientPlayConnectionEvents.DISCONNECT.register((handler, mc) -> {
			ClientState.reset();
			ClientNet.clear();
		});
	}

	public static String tabletKeyName() {
		return tabletKey == null ? "0" : tabletKey.getTranslatedKeyMessage().getString();
	}

	/** Changes the selection locally (instant feedback) and asks the server, which confirms it. */
	public static void selectTablet(boolean selected) {
		if (ClientState.tabletActive() == selected) return;
		ClientState.predictedSelection = selected;
		ClientState.predictionTime = System.currentTimeMillis();
		if (ClientPlayNetworking.canSend(MineSinoNetwork.SelectTablet.TYPE)) {
			ClientPlayNetworking.send(new MineSinoNetwork.SelectTablet(selected));
		}
	}

	/**
	 * Mouse wheel cycles through ten slots: 9 → tablet → 1 and 1 → tablet → 9.
	 * Returning false cancels the vanilla slot change.
	 */
	private static boolean onScroll(Inventory inv, int current, int next, double dx, double dy) {
		boolean forward = Math.floorMod(next - current, Inventory.getSelectionSize()) == 1;
		if (ClientState.tabletActive()) {
			selectTablet(false);
			inv.setSelectedSlot(forward ? 0 : Inventory.getSelectionSize() - 1);
			return false;
		}
		int last = Inventory.getSelectionSize() - 1;
		if ((current == last && forward) || (current == 0 && !forward)) {
			selectTablet(true);
			return false;
		}
		return true;
	}

	/** Right click with the tablet selected (called from the Minecraft mixin). */
	public static void useTablet(Minecraft mc) {
		if (mc.player == null) return;
		if (mc.player.isShiftKeyDown() && mc.hitResult instanceof EntityHitResult ehr) {
			requestMobSale(ehr.getEntity().getId());
			return;
		}
		long now = System.currentTimeMillis();
		if (now - lastOpenRequest < 600 || mc.gui.screen() != null) return;
		lastOpenRequest = now;
		if (ClientPlayNetworking.canSend(MineSinoNetwork.OpenTablet.TYPE)) ClientPlayNetworking.send(new MineSinoNetwork.OpenTablet());
	}

	public static void requestMobSale(int entityId) {
		long now = System.currentTimeMillis();
		if (now - lastMobRequest < 500 || !ClientNet.available()) return;
		lastMobRequest = now;
		JsonObject p = new JsonObject();
		p.addProperty("entity", entityId);
		ClientNet.send("mob", "request", p, reply -> {
			Minecraft mc = Minecraft.getInstance();
			if (!reply.ok()) {
				ClientState.toast(Ui.tr("minesino.mob.title"), Ui.tr(reply.error()), Theme.RED);
				return;
			}
			if (reply.data().has("sold")) {
				ClientState.toast(Ui.tr("minesino.mob.sold"), ClientState.moneySigned(Json.getLong(reply.data(), "price", 0)), Theme.GREEN);
			} else if (mc.gui.screen() == null) {
				mc.gui.setScreen(new MobSaleScreen(reply.data()));
			}
		});
	}

	private static void registerReceivers() {
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.Balance.TYPE, (p, ctx) -> ClientState.setBalance(p.balance()));
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.TabletSync.TYPE, (p, ctx) -> {
			ClientState.tabletSelected = p.selected();
			ClientState.predictedSelection = null;
			ClientState.applySettings(p.settingsJson());
		});
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.TabletHolder.TYPE, (p, ctx) -> {
			if (p.holding()) ClientState.tabletHolders.add(p.entityId());
			else ClientState.tabletHolders.remove(p.entityId());
		});
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.OpenScreen.TYPE, (p, ctx) -> {
			Minecraft mc = ctx.client();
			TabletData data = new TabletData(p.json());
			if (mc.gui.screen() instanceof MineSinoScreen) return;
			mc.gui.setScreen(new MineSinoScreen(data));
		});
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.Response.TYPE, (p, ctx) -> ClientNet.onResponse(p));
		ClientPlayNetworking.registerGlobalReceiver(MineSinoNetwork.Notify.TYPE, (p, ctx) -> onNotify(p.kind(), p.json()));
	}

	private static void onNotify(String kind, String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			return;
		}
		if (o.has("balance")) ClientState.setBalance(o.get("balance").getAsLong());
		if (kind.equals("game_event")) {
			Minecraft mc = Minecraft.getInstance();
			String game = Json.getString(o, "game", "");
			if (mc.gui.screen() instanceof MineSinoScreen s && s.page() instanceof dev.minesino.client.gui.games.GamePage gp && gp.gameId().equals(game)) {
				gp.onServerEvent(o);
				return;
			}
			kind = "round_settled"; // not looking at the game: show a notification instead
		}
		if (kind.equals("round_settled")) {
			String game = Ui.tr("minesino.game." + Json.getString(o, "game", "casino"));
			long payout = Json.getLong(o, "payout", 0), bet = Json.getLong(o, "bet", 0);
			long net = payout - bet;
			ClientState.toast(game + " · " + Ui.tr("minesino.toast.auto_settled"),
				net >= 0 ? ClientState.moneySigned(payout) : ClientState.moneySigned(net), net >= 0 ? Theme.GREEN : Theme.RED);
		}
	}
}
