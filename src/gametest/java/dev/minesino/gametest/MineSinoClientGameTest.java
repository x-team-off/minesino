package dev.minesino.gametest;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.MineSinoClient;
import dev.minesino.client.gui.MineSinoScreen;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.games.GamePage;
import dev.minesino.client.gui.games.GamePages;
import dev.minesino.client.gui.page.CasinoPage;
import dev.minesino.client.gui.page.HistoryPage;
import dev.minesino.client.gui.page.SellPage;
import dev.minesino.client.gui.page.SettingsPage;
import dev.minesino.client.gui.page.ShopPage;
import dev.minesino.client.gui.page.StatsPage;
import dev.minesino.client.gui.widget.Widget;
import dev.minesino.server.MineSinoServer;
import dev.minesino.tablet.TabletItems;
import java.lang.reflect.Field;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.api.client.gametest.v1.world.TestWorldSave;
import net.minecraft.client.Minecraft;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * End-to-end test in a real Minecraft 26.3 client with an integrated server. Every step goes through
 * the real network protocol, server validation and storage. Screenshots are written to
 * build/run/clientGameTest/screenshots for visual review.
 */
public class MineSinoClientGameTest implements FabricClientGameTest {
	private static final Logger LOG = LoggerFactory.getLogger("MineSinoTest");

	@Override
	public void runTest(ClientGameTestContext ctx) {
		TestWorldSave save;
		long balanceBeforeLeave;
		UUID playerId;
		try (TestSingleplayerContext sp = ctx.worldBuilder().create()) {
			sp.getConnection().waitForChunksRender();
			save = sp.getWorldSave();
			playerId = sp.getServer().computeOnServer(s -> s.getPlayerList().getPlayers().getFirst().getUUID());

			// ---------------------------------------------------------- price scale on the real 26.3 recipes
			for (String id : new String[] {"minecraft:dirt", "minecraft:cobblestone", "minecraft:oak_planks", "minecraft:oak_slab",
				"minecraft:iron_ingot", "minecraft:iron_block", "minecraft:diamond", "minecraft:netherite_ingot",
				"minecraft:netherite_helmet", "minecraft:netherite_chestplate", "minecraft:netherite_leggings", "minecraft:netherite_boots",
				"minecraft:netherite_sword", "minecraft:beacon", "minecraft:dragon_egg"}) {
				String v = sp.getServer().computeOnServer(srv -> String.valueOf(MineSinoServer.get().prices().unitPrice(id)));
				LOG.info("[MineSinoTest] price {} = {} (1/100 MS)", id, v);
			}
			long chest = sp.getServer().computeOnServer(srv -> MineSinoServer.get().prices().unitPrice("minecraft:netherite_chestplate").floor());
			check(chest >= 3_000_000 && chest <= 5_000_000, "netherite chestplate ~40k MS: " + chest / 100);
			long egg = sp.getServer().computeOnServer(srv -> MineSinoServer.get().prices().unitPrice("minecraft:dragon_egg").floor());
			check(egg == 100_000_000, "dragon egg = 1 000 000 MS");
			// full price table for review: build/run/clientGameTest/prices_dump.tsv (id, MS, category)
			sp.getServer().runOnServer(srv -> {
				MineSinoServer ms = MineSinoServer.get();
				StringBuilder sb = new StringBuilder();
				net.minecraft.core.registries.BuiltInRegistries.ITEM.keySet().stream().map(Object::toString).sorted().forEach(id -> {
					var pr = ms.prices().unitPrice(id);
					sb.append(id).append('\t').append(pr == null ? "-" : String.valueOf(pr.floor() / 100.0)).append('\t').append(ms.prices().category(id)).append('\n');
				});
				try {
					java.nio.file.Files.writeString(java.nio.file.Path.of("prices_dump.tsv"), sb);
				} catch (java.io.IOException e) {
					throw new IllegalStateException(e);
				}
			});
			String golem = sp.getServer().computeOnServer(srv -> String.valueOf(MineSinoServer.get().mobPrices().price("minecraft:iron_golem")));
			LOG.info("[MineSinoTest] iron golem price = {}", golem);

			// ---------------------------------------------------------- balance & HUD
			ctx.waitFor(mc -> ClientState.balanceLoaded, 200);
			check(ClientState.balance == 0, "no starting balance, got " + ClientState.balance);
			sp.getServer().runCommand("balance add Player0 100000");
			ctx.waitFor(mc -> ClientState.balance == 10_000_000, 100);
			ctx.takeScreenshot("01_hud_slot_not_selected");

			// ---------------------------------------------------------- tenth slot via key 0
			ctx.getInput().pressKey(MineSinoClient.tabletKey);
			ctx.waitFor(mc -> ClientState.tabletSelected, 100);
			check(sp.getServer().computeOnServer(s -> MineSinoServer.get().tablet.isSelected(playerId)), "server confirms tablet selection");
			ctx.getInput().pressKey(MineSinoClient.tabletKey);
			ctx.waitTicks(5);
			check(ClientState.tabletSelected, "pressing 0 again keeps the tablet selected");
			ctx.takeScreenshot("02_hud_tablet_selected");

			// Q must not drop anything while the tablet is selected
			sp.getServer().runCommand("give Player0 minecraft:diamond 3");
			ctx.waitTicks(5);
			int diamondsBefore = countItem(sp, Items.DIAMOND);
			ctx.getInput().pressKey(o -> o.keyDrop);
			ctx.waitTicks(5);
			check(countItem(sp, Items.DIAMOND) == diamondsBefore, "Q does nothing while the tablet is held");

			// forged tablet items are deleted
			sp.getServer().runCommand("give Player0 minesino:tablet 1");
			ctx.waitTicks(30);
			check(countItem(sp, TabletItems.TABLET) == 0, "forged tablet item removed from inventory");

			// key 1 leaves the tablet slot; scroll back into it
			ctx.getInput().pressKey(o -> o.keyHotbarSlots[0]);
			ctx.waitFor(mc -> !ClientState.tabletSelected, 100);
			ctx.getInput().scroll(1);
			ctx.waitTicks(5);
			ctx.getInput().scroll(-1);
			ctx.waitTicks(5);
			ctx.getInput().pressKey(MineSinoClient.tabletKey);
			ctx.waitFor(mc -> ClientState.tabletSelected, 100);

			// third person: the tablet is held in the hand, as other players see it
			ctx.runOnClient(mc -> mc.gui.toastManager().clear()); // vanilla advancement toasts would cover the screenshots
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.THIRD_PERSON_FRONT));
			ctx.waitTicks(10);
			ctx.takeScreenshot("05_third_person_tablet");
			ctx.runOnClient(mc -> mc.options.setCameraType(net.minecraft.client.CameraType.FIRST_PERSON));
			ctx.waitTicks(2);

			// ---------------------------------------------------------- tablet UI via right click
			ctx.getInput().pressKey(o -> o.keyUse);
			ctx.waitForScreen(MineSinoScreen.class);
			ctx.waitTicks(15);
			ctx.runOnClient(mc -> mc.gui.toastManager().clear());
			ctx.waitTicks(2);
			ctx.takeScreenshot("03_home");
			clickWidget(ctx, 0); // first home card = Casino, clicked with the real left button
			ctx.waitFor(mc -> mc.gui.screen() instanceof MineSinoScreen s && s.page() instanceof CasinoPage, 100);
			ctx.waitTicks(15);
			ctx.takeScreenshot("04_casino_lobby");
			// a few past rounds so the "last results" strip on each board has something to show
			for (int i = 0; i < 4; i++) {
				call(ctx, "game:dice", "roll", "{\"bet\":100,\"faces\":[1,2,3]}");
				call(ctx, "game:roulette", "spin", "{\"bets\":[{\"type\":\"red\",\"amount\":100}]}");
				call(ctx, "game:crash", "launch", "{\"bet\":100,\"auto\":101}");
				ctx.waitTicks(10); // auto cash-out at x1.01 (or the rocket blows) on the server tick
			}
			for (String game : GamePages.ORDER) {
				navigate(ctx, () -> GamePages.create(game));
				ctx.takeScreenshot("10_game_" + game);
			}
			// play a few rounds through the real UI buttons
			for (String game : new String[] {"slots", "dice", "coinflip", "plinko", "wheel", "crash"}) {
				navigate(ctx, () -> GamePages.create(game));
				long before = ClientState.balance;
				clickPlay(ctx);
				ctx.waitTicks(12);
				ctx.takeScreenshot("20_play_" + game + "_animating");
				if (game.equals("crash")) {
					ctx.waitTicks(20);
					ctx.takeScreenshot("20_play_crash_flying");
					clickWidget(ctx, 7); // "Cash out" button, real left click while the rocket flies
				}
				ctx.waitFor(mc -> !isAnimating(mc), 400);
				ctx.waitTicks(4);
				ctx.takeScreenshot("21_play_" + game + "_result");
				LOG.info("[MineSinoTest] {} round finished, balance {} -> {}", game, before, ClientState.balance);
			}
			navigate(ctx, SellPage::new);
			ctx.takeScreenshot("30_sell");
			navigate(ctx, ShopPage::new);
			ctx.waitTicks(10);
			ctx.takeScreenshot("31_shop");
			// search: click the field with the real mouse and type with real key events
			ctx.waitFor(mc -> shop(mc).totalCount() > 0, 100);
			int shopTotal = ctx.computeOnClient(mc -> shop(mc).totalCount());
			clickWidget(ctx, 0);
			ctx.waitTicks(2);
			ctx.getInput().typeChars("diamond");
			ctx.waitTicks(5);
			int found = ctx.computeOnClient(mc -> shop(mc).visibleCount());
			LOG.info("[MineSinoTest] shop search 'diamond': {} of {}", found, shopTotal);
			check(found > 0 && found < shopTotal, "shop search filters items: " + found + " of " + shopTotal);
			ctx.getInput().typeChars(" block");
			ctx.waitTicks(5);
			int block = ctx.computeOnClient(mc -> shop(mc).visibleCount());
			check(block >= 1 && block < found, "multi-word search 'diamond block': " + block);
			ctx.takeScreenshot("31_shop_search");
			navigate(ctx, HistoryPage::new);
			ctx.takeScreenshot("32_history");
			navigate(ctx, StatsPage::new);
			ctx.takeScreenshot("33_stats");
			navigate(ctx, SettingsPage::new);
			ctx.takeScreenshot("34_settings");

			// blackjack: start a hand, close the tablet -> server settles it
			navigate(ctx, () -> GamePages.create("blackjack"));
			JsonObject bj = call(ctx, "game:blackjack", "start", "{\"bet\":1000}");
			ctx.waitTicks(10);
			ctx.takeScreenshot("22_blackjack_hand");
			boolean wasActive = "active".equals(bj.get("status").getAsString());
			ctx.runOnClient(mc -> mc.gui.setScreen(null));
			ctx.waitTicks(20);
			if (wasActive) {
				check(!sp.getServer().computeOnServer(s -> MineSinoServer.get().casino.games().get("blackjack") instanceof
					dev.minesino.casino.games.BlackjackGame b && b.hasActive(playerId)), "closing the tablet settles the blackjack hand");
			}
			ctx.takeScreenshot("23_after_close_toast");

			// ---------------------------------------------------------- casino through the protocol
			long b0 = ClientState.balance;
			JsonObject r = call(ctx, "game:roulette", "spin", "{\"bets\":[{\"type\":\"red\",\"amount\":500}]}");
			check(r.has("number"), "roulette answered with a number");
			long expected = b0 - 500 + r.get("payout").getAsLong();
			ctx.waitTicks(3);
			check(ClientState.balance == expected, "roulette balance " + ClientState.balance + " == " + expected);
			JsonObject tooBig = callRaw(ctx, "game:dice", "roll", "{\"bet\":999999999,\"faces\":[1]}");
			check(tooBig.has("error"), "server rejects a bet above max/balance");
			JsonObject forged = callRaw(ctx, "game:dice", "roll", "{\"bet\":1000,\"faces\":[1],\"payout\":99999999}");
			check(!forged.has("error") || forged.get("error").getAsString().length() > 0, "client payout field is ignored");

			// ---------------------------------------------------------- selling items
			sp.getServer().runCommand("clear Player0");
			sp.getServer().runCommand("give Player0 minecraft:iron_ingot 64");
			sp.getServer().runCommand("give Player0 minecraft:oak_slab 10");
			ctx.waitTicks(10);
			long beforeSale = ClientState.balance;
			JsonObject preview = call(ctx, "sell", "preview", "{\"allOf\":\"*\"}");
			long total = preview.get("total").getAsLong();
			check(total == 64 * 10_000 + 10 * 250, "sale preview 64 ingots + 10 slabs = " + total);
			JsonObject wrong = callRaw(ctx, "sell", "sell", "{\"allOf\":\"*\",\"expected\":1}");
			check(wrong.has("error"), "sale with a wrong expected total is refused");
			JsonObject sold = call(ctx, "sell", "sell", "{\"allOf\":\"*\",\"expected\":" + total + "}");
			check(sold.get("sold").getAsBoolean(), "items sold");
			ctx.waitTicks(5);
			check(countItem(sp, Items.IRON_INGOT) == 0, "ingots removed");
			check(ClientState.balance == beforeSale + total, "sale credited exactly once");

			// ---------------------------------------------------------- shop & spawn eggs
			long beforeBuy = ClientState.balance;
			JsonObject q = call(ctx, "buy", "quote", "{\"item\":\"minecraft:cow_spawn_egg\",\"count\":1}");
			long eggPrice = q.get("total").getAsLong();
			call(ctx, "buy", "buy", "{\"item\":\"minecraft:cow_spawn_egg\",\"count\":1,\"expected\":" + eggPrice + "}");
			JsonObject q16 = call(ctx, "buy", "quote", "{\"item\":\"minecraft:oak_planks\",\"count\":16}");
			call(ctx, "buy", "buy", "{\"item\":\"minecraft:oak_planks\",\"count\":16,\"expected\":" + q16.get("total").getAsLong() + "}");
			ctx.waitTicks(5);
			check(countItem(sp, Items.COW_SPAWN_EGG) == 1, "spawn egg delivered");
			check(countItem(sp, Items.OAK_PLANKS) == 16, "16 planks delivered");
			check(ClientState.balance == beforeBuy - eggPrice - q16.get("total").getAsLong(), "purchase charged exactly");
			JsonObject banned = callRaw(ctx, "buy", "quote", "{\"item\":\"minecraft:command_block\",\"count\":1}");
			check(banned.has("error"), "command block cannot be bought");
			JsonObject wither = callRaw(ctx, "buy", "quote", "{\"item\":\"minecraft:wither_spawn_egg\",\"count\":1}");
			check(wither.has("error"), "wither spawn egg cannot be bought");

			// ---------------------------------------------------------- mob sale with confirmation
			sp.getServer().runCommand("summon minecraft:cow ~2 ~ ~");
			ctx.waitTicks(10);
			int cowId = sp.getServer().computeOnServer(s -> {
				var p = s.getPlayerList().getPlayer(playerId);
				for (Entity e : p.level().getEntities(p, p.getBoundingBox().inflate(6))) if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).getPath().equals("cow")) return e.getId();
				return -1;
			});
			check(cowId >= 0, "cow summoned");
			long beforeMob = ClientState.balance;
			JsonObject offer = call(ctx, "mob", "request", "{\"entity\":" + cowId + "}");
			long price = offer.get("price").getAsLong();
			check(price == 4000, "cow sells for 40 MS, offer " + price);
			call(ctx, "mob", "confirm", "{\"offer\":" + offer.get("offer").getAsLong() + "}");
			JsonObject again = callRaw(ctx, "mob", "confirm", "{\"offer\":" + offer.get("offer").getAsLong() + "}");
			check(again.has("error"), "the same offer cannot be paid twice");
			ctx.waitTicks(3);
			check(ClientState.balance == beforeMob + price, "mob sale credited once");
			JsonObject self = callRaw(ctx, "mob", "request", "{\"entity\":" + ctx.computeOnClient(mc -> mc.player.getId()) + "}");
			check(self.has("error"), "players cannot be sold");

			// ---------------------------------------------------------- admin command (singleplayer host is op)
			long beforeAdmin = ClientState.balance;
			sp.getServer().runCommand("balance add Player0 250");
			ctx.waitTicks(5);
			check(ClientState.balance == beforeAdmin + 25_000, "/balance add synced to the client");
			balanceBeforeLeave = ClientState.balance;
			LOG.info("[MineSinoTest] balance before leaving the world: {}", balanceBeforeLeave);
		}

		// ---------------------------------------------------------- relog: balance must persist
		try (TestSingleplayerContext sp = save.open()) {
			sp.getConnection().waitForChunksRender();
			ctx.waitFor(mc -> ClientState.balanceLoaded, 200);
			ctx.waitTicks(5);
			check(ClientState.balance == balanceBeforeLeave, "balance after rejoin " + ClientState.balance + " == " + balanceBeforeLeave);
			check(!ClientState.tabletSelected, "rejoin starts on the normal hotbar");
			ctx.takeScreenshot("40_after_rejoin");
		}
		LOG.info("[MineSinoTest] ALL CHECKS PASSED");
	}

	// ------------------------------------------------------------------ helpers

	private static void check(boolean condition, String what) {
		if (!condition) throw new AssertionError("[MineSinoTest] FAILED: " + what);
		LOG.info("[MineSinoTest] ok: {}", what);
	}

	private static int countItem(TestSingleplayerContext sp, net.minecraft.world.item.Item item) {
		return sp.getServer().computeOnServer(s -> {
			Inventory inv = s.getPlayerList().getPlayers().getFirst().getInventory();
			int n = 0;
			for (int i = 0; i < inv.getContainerSize(); i++) {
				ItemStack st = inv.getItem(i);
				if (st.is(item)) n += st.getCount();
			}
			return n;
		});
	}

	private static void navigate(ClientGameTestContext ctx, Supplier<Page> page) {
		ctx.runOnClient(mc -> {
			if (mc.gui.screen() instanceof MineSinoScreen s) s.navigate(page.get());
		});
		ctx.waitTicks(15);
	}

	private static Widget playButton(Minecraft mc) throws ReflectiveOperationException {
		Page p = ((MineSinoScreen) mc.gui.screen()).page();
		Field f = GamePage.class.getDeclaredField("playButton");
		f.setAccessible(true);
		return (Widget) f.get(p);
	}

	private static boolean isAnimating(Minecraft mc) {
		try {
			Page p = ((MineSinoScreen) mc.gui.screen()).page();
			Field f = GamePage.class.getDeclaredField("animating");
			f.setAccessible(true);
			Field b = Page.class.getDeclaredField("busy");
			b.setAccessible(true);
			return f.getBoolean(p) || b.getBoolean(p);
		} catch (ReflectiveOperationException e) {
			return false;
		}
	}

	private static ShopPage shop(net.minecraft.client.Minecraft mc) {
		return (ShopPage) ((MineSinoScreen) mc.gui.screen()).page();
	}

	/** Clicks the n-th widget of the current page with the real left mouse button. */
	private static void clickWidget(ClientGameTestContext ctx, int index) {
		double[] pos = ctx.computeOnClient(mc -> {
			Page p = ((MineSinoScreen) mc.gui.screen()).page();
			try {
				Field f = Page.class.getDeclaredField("widgets");
				f.setAccessible(true);
				Widget w = ((java.util.List<Widget>) f.get(p)).get(index);
				double scale = mc.getWindow().getGuiScale();
				return new double[] {(w.x + w.w / 2) * scale, (w.y + w.h / 2) * scale};
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTick();
		ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
	}

	private static void clickPlay(ClientGameTestContext ctx) {
		double[] pos = ctx.computeOnClient(mc -> {
			Widget w;
			try {
				w = playButton(mc);
			} catch (ReflectiveOperationException e) {
				throw new IllegalStateException(e);
			}
			double scale = mc.getWindow().getGuiScale();
			return new double[] {(w.x + w.w / 2) * scale, (w.y + w.h / 2) * scale};
		});
		ctx.getInput().setCursorPos(pos[0], pos[1]);
		ctx.waitTick();
		ctx.getInput().pressMouse(com.mojang.blaze3d.platform.InputConstants.MOUSE_BUTTON_LEFT);
	}

	private static JsonObject callRaw(ClientGameTestContext ctx, String channel, String action, String json) {
		ctx.waitTicks(4); // respect the server's per-player request cooldown
		AtomicReference<ClientNet.Reply> ref = new AtomicReference<>();
		ctx.runOnClient(mc -> ClientNet.send(channel, action, JsonParser.parseString(json).getAsJsonObject(), ref::set));
		ctx.waitFor(mc -> ref.get() != null, 200);
		ClientNet.Reply r = ref.get();
		JsonObject d = r.data().deepCopy();
		if (!r.ok() && !d.has("error")) d.addProperty("error", "unknown");
		LOG.info("[MineSinoTest] {} {} -> {}", channel, action, d);
		return d;
	}

	private static JsonObject call(ClientGameTestContext ctx, String channel, String action, String json) {
		JsonObject d = callRaw(ctx, channel, action, json);
		check(!d.has("error"), channel + " " + action + " succeeded (" + d + ")");
		return d;
	}
}
