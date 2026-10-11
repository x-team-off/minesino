package dev.minesino.client.gui;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;

/** Data the server sends when the tablet opens: limits, enabled games and permissions. */
public final class TabletData {
	public final JsonObject casino;
	public final long minBet, maxBet;
	public final List<Long> quickBets = new ArrayList<>();
	public final boolean casinoEnabled, sellEnabled, shopEnabled, admin;
	public final String playerName;

	public TabletData(String json) {
		JsonObject o;
		try {
			o = JsonParser.parseString(json).getAsJsonObject();
		} catch (RuntimeException e) {
			o = new JsonObject();
		}
		casino = o.has("casino") ? o.getAsJsonObject("casino") : new JsonObject();
		minBet = Json.getLong(casino, "minBet", 100);
		maxBet = Json.getLong(casino, "maxBet", 10_000_000);
		if (casino.has("quickBets")) {
			for (JsonElement e : casino.getAsJsonArray("quickBets")) quickBets.add(e.getAsLong());
		}
		casinoEnabled = Json.getBool(o, "casinoEnabled", true);
		sellEnabled = Json.getBool(o, "sellEnabled", true);
		shopEnabled = Json.getBool(o, "shopEnabled", true);
		admin = Json.getBool(o, "admin", false);
		playerName = Json.getString(o, "name", "");
	}

	public boolean gameEnabled(String id) {
		return casino.has("games") && casino.getAsJsonObject("games").has(id);
	}

	public JsonObject game(String id) {
		return gameEnabled(id) ? casino.getAsJsonObject("games").getAsJsonObject(id) : new JsonObject();
	}

	public static long[] longs(JsonArray a) {
		long[] r = new long[a.size()];
		for (int i = 0; i < r.length; i++) r[i] = a.get(i).getAsLong();
		return r;
	}
}
