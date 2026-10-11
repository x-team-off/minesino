package dev.minesino.economy;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.UUID;

/** Small, defensive JSON helpers used by persistence code. */
public final class Json {
	private Json() {}

	public static long getLong(JsonObject o, String key, long def) {
		try {
			JsonElement e = o.get(key);
			return e == null || e.isJsonNull() ? def : e.getAsLong();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	public static int getInt(JsonObject o, String key, int def) {
		try {
			JsonElement e = o.get(key);
			return e == null || e.isJsonNull() ? def : e.getAsInt();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	public static String getString(JsonObject o, String key, String def) {
		try {
			JsonElement e = o.get(key);
			return e == null || e.isJsonNull() ? def : e.getAsString();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	public static boolean getBool(JsonObject o, String key, boolean def) {
		try {
			JsonElement e = o.get(key);
			return e == null || e.isJsonNull() ? def : e.getAsBoolean();
		} catch (RuntimeException ex) {
			return def;
		}
	}

	static JsonObject txToJson(Transaction t) {
		JsonObject o = new JsonObject();
		o.addProperty("id", t.id());
		o.addProperty("t", t.time());
		o.addProperty("type", t.type().name());
		o.addProperty("d", t.delta());
		o.addProperty("b", t.balanceAfter());
		o.addProperty("detail", t.detail());
		if (!t.outcome().isEmpty()) o.addProperty("out", t.outcome());
		o.addProperty("op", t.opId());
		return o;
	}

	static Transaction txFromJson(JsonObject o, UUID player) {
		try {
			return new Transaction(o.get("id").getAsLong(), o.get("t").getAsLong(), player,
				TxType.valueOf(o.get("type").getAsString()), o.get("d").getAsLong(), o.get("b").getAsLong(),
				getString(o, "detail", ""), getString(o, "out", ""), o.get("op").getAsString());
		} catch (RuntimeException e) {
			return null;
		}
	}

	static JsonObject statsToJson(PlayerStats s) {
		JsonObject o = new JsonObject();
		o.addProperty("rounds", s.roundsPlayed);
		o.addProperty("wins", s.wins);
		o.addProperty("losses", s.losses);
		o.addProperty("pushes", s.pushes);
		o.addProperty("wagered", s.totalWagered);
		o.addProperty("paidOut", s.totalPaidOut);
		o.addProperty("itemsSold", s.itemsSold);
		o.addProperty("itemIncome", s.itemSaleIncome);
		o.addProperty("mobsSold", s.mobsSold);
		o.addProperty("mobIncome", s.mobSaleIncome);
		o.addProperty("purchases", s.purchases);
		o.addProperty("spent", s.purchaseSpent);
		o.addProperty("biggestWin", s.biggestWin);
		return o;
	}

	static void statsFromJson(JsonObject o, PlayerStats s) {
		s.roundsPlayed = getLong(o, "rounds", 0);
		s.wins = getLong(o, "wins", 0);
		s.losses = getLong(o, "losses", 0);
		s.pushes = getLong(o, "pushes", 0);
		s.totalWagered = getLong(o, "wagered", 0);
		s.totalPaidOut = getLong(o, "paidOut", 0);
		s.itemsSold = getLong(o, "itemsSold", 0);
		s.itemSaleIncome = getLong(o, "itemIncome", 0);
		s.mobsSold = getLong(o, "mobsSold", 0);
		s.mobSaleIncome = getLong(o, "mobIncome", 0);
		s.purchases = getLong(o, "purchases", 0);
		s.purchaseSpent = getLong(o, "spent", 0);
		s.biggestWin = getLong(o, "biggestWin", 0);
	}
}
