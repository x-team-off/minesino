package dev.minesino.client;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.minesino.network.MineSinoNetwork;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;

/** Sends requests with fresh random ids and routes answers back to the requesting UI. */
public final class ClientNet {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final Map<Long, Consumer<Reply>> callbacks = new HashMap<>();

	private ClientNet() {}

	/** Server answer. {@code error} is a translation key when {@code ok} is false. */
	public record Reply(boolean ok, JsonObject data, String error) {}

	public static boolean available() {
		return ClientPlayNetworking.canSend(MineSinoNetwork.Action.TYPE);
	}

	public static long send(String channel, String action, JsonObject params, Consumer<Reply> onReply) {
		long id;
		do {
			id = RANDOM.nextLong();
		} while (id == 0);
		if (onReply != null) callbacks.put(id, onReply);
		if (callbacks.size() > 256) callbacks.clear(); // stale entries from a closed UI
		ClientPlayNetworking.send(new MineSinoNetwork.Action(id, channel, action, params == null ? "{}" : params.toString()));
		return id;
	}

	static void onResponse(MineSinoNetwork.Response r) {
		JsonObject data;
		try {
			data = JsonParser.parseString(r.json()).getAsJsonObject();
		} catch (RuntimeException e) {
			data = new JsonObject();
		}
		if (data.has("balance")) {
			try {
				ClientState.setBalance(data.get("balance").getAsLong());
			} catch (RuntimeException ignored) {
				// display only
			}
		}
		Consumer<Reply> cb = callbacks.remove(r.requestId());
		if (cb == null) return;
		String error = !r.ok() && data.has("error") ? data.get("error").getAsString() : null;
		cb.accept(new Reply(r.ok(), data, error));
	}

	public static void clear() {
		callbacks.clear();
	}
}
