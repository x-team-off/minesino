package dev.minesino.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minesino.casino.GameException;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.Json;
import dev.minesino.economy.PlayerStats;
import dev.minesino.economy.Transaction;
import dev.minesino.economy.TxType;
import dev.minesino.mobs.MobSaleService;
import dev.minesino.network.MineSinoNetwork;
import dev.minesino.selling.ItemSaleService;
import dev.minesino.selling.SalePlanner;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.server.level.ServerPlayer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Validates and dispatches every client request. Runs on the server thread. */
public final class ServerPackets {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Net");

	private ServerPackets() {}

	public static void register() {
		ServerPlayNetworking.registerGlobalReceiver(MineSinoNetwork.SelectTablet.TYPE, (p, ctx) -> run(ctx, () -> onSelect(ctx.player(), p.selected())));
		ServerPlayNetworking.registerGlobalReceiver(MineSinoNetwork.OpenTablet.TYPE, (p, ctx) -> run(ctx, () -> onOpen(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(MineSinoNetwork.TabletClosed.TYPE, (p, ctx) -> run(ctx, () -> onClosed(ctx.player())));
		ServerPlayNetworking.registerGlobalReceiver(MineSinoNetwork.Action.TYPE, (p, ctx) -> run(ctx, () -> onAction(ctx.player(), p)));
	}

	private static void run(ServerPlayNetworking.Context ctx, Runnable r) {
		if (ctx.server().isSameThread()) r.run();
		else ctx.server().execute(r);
	}

	private static void onSelect(ServerPlayer player, boolean selected) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) return;
		if (player.isSpectator()) selected = false;
		ms.tablet.select(player.getUUID(), selected);
		ms.sendTabletSync(player);
	}

	private static void onOpen(ServerPlayer player) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) return;
		if (!ms.tablet.isSelected(player.getUUID()) || player.isSpectator()) {
			ms.journal.security(player.getGameProfile().name(), "tried to open the tablet without selecting the tablet slot");
			ms.sendTabletSync(player);
			return;
		}
		JsonObject o = new JsonObject();
		o.add("casino", ms.casino.describe());
		o.addProperty("casinoEnabled", ms.config().casino.enabled);
		o.addProperty("sellEnabled", ms.config().selling.enabled);
		o.addProperty("shopEnabled", ms.config().shop.enabled);
		o.addProperty("admin", ms.isAdmin(player));
		o.addProperty("balance", ms.economy.balance(player.getUUID()));
		o.addProperty("name", player.getGameProfile().name());
		ServerPlayNetworking.send(player, new MineSinoNetwork.OpenScreen(o.toString()));
	}

	private static void onClosed(ServerPlayer player) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) return;
		Map<String, JsonObject> settled = ms.casino.abandon(player.getUUID());
		for (Map.Entry<String, JsonObject> e : settled.entrySet()) {
			JsonObject n = e.getValue().deepCopy();
			n.addProperty("game", e.getKey());
			ServerPlayNetworking.send(player, new MineSinoNetwork.Notify("round_settled", n.toString()));
		}
		ms.mobs.cancelOffers(player.getUUID());
	}

	private static void onAction(ServerPlayer player, MineSinoNetwork.Action a) {
		MineSinoServer ms = MineSinoServer.get();
		if (ms == null) return;
		String who = player.getGameProfile().name();
		var lim = ms.config().limits;
		RequestGuard.Verdict v = ms.guard.check(player.getUUID(), a.requestId(), ms.ticks(), System.currentTimeMillis(),
			lim.requestCooldownTicks, lim.maxRequestsPerSecond);
		if (v != RequestGuard.Verdict.OK) {
			if (v == RequestGuard.Verdict.REPLAY || v == RequestGuard.Verdict.INVALID) {
				ms.journal.security(who, "rejected " + v + " request " + a.requestId() + " on " + a.channel());
				return; // a replay gets no answer at all
			}
			fail(player, a, "minesino.error.too_fast", "");
			return;
		}
		JsonObject params;
		try {
			JsonElement el = a.json().isEmpty() ? new JsonObject() : JsonParser.parseString(a.json());
			if (!el.isJsonObject()) throw new IllegalArgumentException();
			params = el.getAsJsonObject();
		} catch (RuntimeException e) {
			ms.journal.security(who, "malformed request on " + a.channel());
			fail(player, a, "minesino.error.invalid_params", "");
			return;
		}
		boolean isMob = a.channel().equals("mob");
		boolean tabletRequired = !isMob || ms.config().mobs.requireTablet;
		if (tabletRequired && !ms.tablet.isSelected(player.getUUID())) {
			ms.journal.security(who, "request on " + a.channel() + " without the tablet selected");
			fail(player, a, "minesino.error.tablet_required", "");
			return;
		}
		try {
			String channel = a.channel();
			if (channel.startsWith("game:")) {
				JsonObject res = ms.casino.handle(player.getUUID(), channel.substring(5), a.action(), params);
				ok(player, a, res);
			} else {
				switch (channel) {
					case "sell" -> handleSell(ms, player, a, params);
					case "buy" -> handleBuy(ms, player, a, params);
					case "mob" -> handleMob(ms, player, a, params);
					case "data" -> handleData(ms, player, a, params);
					case "admin" -> handleAdmin(ms, player, a);
					default -> {
						ms.journal.security(who, "unknown channel " + channel);
						fail(player, a, "minesino.error.invalid_action", "");
					}
				}
			}
		} catch (GameException e) {
			fail(player, a, e.key(), e.arg());
		} catch (RuntimeException e) {
			LOG.error("Request {} {} from {} failed", a.channel(), a.action(), who, e);
			fail(player, a, "minesino.error.internal", "");
		}
	}

	private static String opId(ServerPlayer p, MineSinoNetwork.Action a) {
		return a.channel() + ":" + p.getUUID() + ":" + Long.toHexString(a.requestId());
	}

	private static void handleSell(MineSinoServer ms, ServerPlayer player, MineSinoNetwork.Action a, JsonObject params) {
		ItemSaleService s = ms.selling;
		switch (a.action()) {
			case "inventory" -> ok(player, a, s.inventoryView(player));
			case "preview", "sell" -> {
				List<SalePlanner.Line> lines = ItemSaleService.parseLines(params);
				SalePlanner.Plan plan = params.has("allOf") ? s.previewAll(player, Json.getString(params, "allOf", "*").equals("*") ? null : Json.getString(params, "allOf", ""))
					: s.preview(player, lines);
				if (!plan.ok()) {
					fail(player, a, plan.error(), "");
					return;
				}
				JsonObject res = ItemSaleService.planJson(plan);
				if (a.action().equals("sell")) {
					long expected = Json.getLong(params, "expected", -1);
					if (expected != plan.total()) {
						res.addProperty("error", "minesino.error.price_changed");
						sendResponse(player, a, false, res);
						return;
					}
					String err = s.execute(player, plan, opId(player, a));
					if (err != null) {
						fail(player, a, err, "");
						return;
					}
					res.addProperty("sold", true);
					res.add("inventory", s.inventoryView(player).get("slots"));
				}
				res.addProperty("balance", ms.economy.balance(player.getUUID()));
				ok(player, a, res);
			}
			default -> fail(player, a, "minesino.error.invalid_action", "");
		}
	}

	private static void handleBuy(MineSinoServer ms, ServerPlayer player, MineSinoNetwork.Action a, JsonObject params) {
		switch (a.action()) {
			case "catalog" -> sendResponse(player, a, true, JsonParser.parseString(ms.shop.catalog()).getAsJsonObject());
			case "quote" -> {
				var o = ms.shop.quote(Json.getString(params, "item", ""), Json.getInt(params, "count", 0));
				if (!o.ok()) {
					fail(player, a, o.error(), "");
					return;
				}
				JsonObject res = new JsonObject();
				res.addProperty("total", o.price());
				res.addProperty("unit", o.unitPriceHint());
				ok(player, a, res);
			}
			case "buy" -> {
				JsonObject res = new JsonObject();
				String err = ms.shop.buy(player, Json.getString(params, "item", ""), Json.getInt(params, "count", 0),
					Json.getLong(params, "expected", -1), opId(player, a), res);
				if (err != null) {
					res.addProperty("error", err);
					sendResponse(player, a, false, res);
					return;
				}
				res.addProperty("balance", ms.economy.balance(player.getUUID()));
				ok(player, a, res);
			}
			default -> fail(player, a, "minesino.error.invalid_action", "");
		}
	}

	private static void handleMob(MineSinoServer ms, ServerPlayer player, MineSinoNetwork.Action a, JsonObject params) {
		MobSaleService.Result r;
		switch (a.action()) {
			case "request" -> r = ms.mobs.request(player, Json.getInt(params, "entity", -1), opId(player, a));
			case "confirm" -> r = ms.mobs.confirm(player, Json.getLong(params, "offer", -1));
			case "cancel" -> {
				ms.mobs.cancel(player, Json.getLong(params, "offer", -1));
				ok(player, a, new JsonObject());
				return;
			}
			default -> {
				fail(player, a, "minesino.error.invalid_action", "");
				return;
			}
		}
		if (r.error() != null) fail(player, a, r.error(), "");
		else {
			r.data().addProperty("balance", ms.economy.balance(player.getUUID()));
			ok(player, a, r.data());
		}
	}

	private static void handleData(MineSinoServer ms, ServerPlayer player, MineSinoNetwork.Action a, JsonObject params) {
		UUID id = player.getUUID();
		switch (a.action()) {
			case "history" -> {
				TxType.Group group;
				try {
					group = TxType.Group.valueOf(Json.getString(params, "group", "ALL"));
				} catch (IllegalArgumentException e) {
					group = TxType.Group.ALL;
				}
				ok(player, a, historyJson(ms.economy.history(id, group, Json.getInt(params, "page", 0), 10)));
			}
			case "stats" -> ok(player, a, statsJson(ms.economy.stats(id), ms.economy.balance(id)));
			case "recent" -> {
				JsonObject o = new JsonObject();
				o.add("recent", ms.casino.recent(id, Json.getString(params, "game", "")));
				ok(player, a, o);
			}
			default -> fail(player, a, "minesino.error.invalid_action", "");
		}
	}

	private static void handleAdmin(MineSinoServer ms, ServerPlayer player, MineSinoNetwork.Action a) {
		if (!ms.isAdmin(player)) {
			ms.journal.security(player.getGameProfile().name(), "non-admin used admin channel");
			fail(player, a, "minesino.error.no_permission", "");
			return;
		}
		JsonObject o = new JsonObject();
		switch (a.action()) {
			case "reload" -> {
				List<String> w = ms.reload();
				ms.journal.admin(player.getGameProfile().name(), "reload via tablet (" + w.size() + " warnings)");
				o.addProperty("warnings", w.size());
				JsonArray arr = new JsonArray();
				w.stream().limit(30).forEach(arr::add);
				o.add("messages", arr);
			}
			case "overview" -> {
				o.addProperty("accounts", ms.economy.accountCount());
				o.addProperty("supply", ms.economy.totalSupply());
				o.addProperty("pricedItems", ms.prices().all().size());
				o.addProperty("pricedMobs", ms.mobPrices().all().size());
				o.addProperty("priceWarnings", ms.prices().warnings().size());
			}
			default -> {
				fail(player, a, "minesino.error.invalid_action", "");
				return;
			}
		}
		ok(player, a, o);
	}

	public static JsonObject historyJson(EconomyManager.HistoryPage page) {
		JsonObject o = new JsonObject();
		o.addProperty("page", page.page());
		o.addProperty("pages", page.totalPages());
		o.addProperty("total", page.totalEntries());
		JsonArray arr = new JsonArray();
		for (Transaction t : page.entries()) {
			JsonObject e = new JsonObject();
			e.addProperty("time", t.time());
			e.addProperty("type", t.type().name());
			e.addProperty("delta", t.delta());
			e.addProperty("balance", t.balanceAfter());
			e.addProperty("detail", t.detail());
			e.addProperty("outcome", t.outcome());
			arr.add(e);
		}
		o.add("entries", arr);
		return o;
	}

	public static JsonObject statsJson(PlayerStats s, long balance) {
		JsonObject o = new JsonObject();
		o.addProperty("balance", balance);
		o.addProperty("rounds", s.roundsPlayed);
		o.addProperty("wins", s.wins);
		o.addProperty("losses", s.losses);
		o.addProperty("pushes", s.pushes);
		o.addProperty("wagered", s.totalWagered);
		o.addProperty("paidOut", s.totalPaidOut);
		o.addProperty("net", s.netCasino());
		o.addProperty("biggestWin", s.biggestWin);
		o.addProperty("itemsSold", s.itemsSold);
		o.addProperty("itemIncome", s.itemSaleIncome);
		o.addProperty("mobsSold", s.mobsSold);
		o.addProperty("mobIncome", s.mobSaleIncome);
		o.addProperty("purchases", s.purchases);
		o.addProperty("spent", s.purchaseSpent);
		return o;
	}

	private static void ok(ServerPlayer p, MineSinoNetwork.Action a, JsonObject json) {
		sendResponse(p, a, true, json);
	}

	private static void fail(ServerPlayer p, MineSinoNetwork.Action a, String key, String arg) {
		JsonObject o = new JsonObject();
		o.addProperty("error", key);
		if (!arg.isEmpty()) o.addProperty("arg", arg);
		MineSinoServer ms = MineSinoServer.get();
		if (ms != null) o.addProperty("balance", ms.economy.balance(p.getUUID()));
		sendResponse(p, a, false, o);
	}

	private static void sendResponse(ServerPlayer p, MineSinoNetwork.Action a, boolean ok, JsonObject json) {
		ServerPlayNetworking.send(p, new MineSinoNetwork.Response(a.requestId(), a.channel(), ok, json.toString()));
	}
}
