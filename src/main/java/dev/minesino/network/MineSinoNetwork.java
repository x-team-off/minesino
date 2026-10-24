package dev.minesino.network;

import dev.minesino.MineSino;
import io.netty.buffer.ByteBuf;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * MineSino network protocol. Clients send only intentions (which game action, which slot, which
 * item, how many); the server answers with authoritative results. No packet lets a client set a
 * balance, a price, an outcome or a payout.
 *
 * <p>Channels of {@link Action}: {@code game:<id>}, {@code sell}, {@code buy}, {@code mob}, {@code data}.
 */
public final class MineSinoNetwork {
	/** Upper bound for client-to-server JSON. */
	public static final int MAX_C2S_JSON = 16 * 1024;
	/** Upper bound for server-to-client JSON (shop catalogue is the largest message). */
	public static final int MAX_S2C_JSON = 900 * 1024;

	private MineSinoNetwork() {}

	private static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> typeOf(String path) {
		return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(MineSino.MOD_ID, path));
	}

	// ----------------------------------------------------------------- client -> server

	/** Select (true) or leave (false) the tablet slot. */
	public record SelectTablet(boolean selected) implements CustomPacketPayload {
		public static final Type<SelectTablet> TYPE = typeOf("select_tablet");
		public static final StreamCodec<ByteBuf, SelectTablet> CODEC = StreamCodec.composite(ByteBufCodecs.BOOL, SelectTablet::selected, SelectTablet::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Right click with the tablet selected. The server verifies the selection before opening. */
	public record OpenTablet() implements CustomPacketPayload {
		public static final Type<OpenTablet> TYPE = typeOf("open_tablet");
		public static final StreamCodec<ByteBuf, OpenTablet> CODEC = StreamCodec.unit(new OpenTablet());

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** The tablet screen was closed: open multi-step rounds are settled. */
	public record TabletClosed() implements CustomPacketPayload {
		public static final Type<TabletClosed> TYPE = typeOf("tablet_closed");
		public static final StreamCodec<ByteBuf, TabletClosed> CODEC = StreamCodec.unit(new TabletClosed());

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Generic request. {@code requestId} is random and never reused (replay protection). */
	public record Action(long requestId, String channel, String action, String json) implements CustomPacketPayload {
		public static final Type<Action> TYPE = typeOf("action");
		public static final StreamCodec<ByteBuf, Action> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, Action::requestId,
			ByteBufCodecs.stringUtf8(64), Action::channel,
			ByteBufCodecs.stringUtf8(32), Action::action,
			ByteBufCodecs.stringUtf8(MAX_C2S_JSON), Action::json,
			Action::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	// ----------------------------------------------------------------- server -> client

	public record Balance(long balance) implements CustomPacketPayload {
		public static final Type<Balance> TYPE = typeOf("balance");
		public static final StreamCodec<ByteBuf, Balance> CODEC = StreamCodec.composite(ByteBufCodecs.VAR_LONG, Balance::balance, Balance::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Server-confirmed tablet selection plus display settings (currency symbol, decimals). */
	public record TabletSync(boolean selected, String settingsJson) implements CustomPacketPayload {
		public static final Type<TabletSync> TYPE = typeOf("tablet_sync");
		public static final StreamCodec<ByteBuf, TabletSync> CODEC = StreamCodec.composite(
			ByteBufCodecs.BOOL, TabletSync::selected, ByteBufCodecs.stringUtf8(4096), TabletSync::settingsJson, TabletSync::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Another player (by entity id) took the tablet into their hand or put it away. */
	public record TabletHolder(int entityId, boolean holding) implements CustomPacketPayload {
		public static final Type<TabletHolder> TYPE = typeOf("tablet_holder");
		public static final StreamCodec<ByteBuf, TabletHolder> CODEC = StreamCodec.composite(
			ByteBufCodecs.VAR_INT, TabletHolder::entityId, ByteBufCodecs.BOOL, TabletHolder::holding, TabletHolder::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Opens the tablet UI with casino limits, enabled games and permissions. */
	public record OpenScreen(String json) implements CustomPacketPayload {
		public static final Type<OpenScreen> TYPE = typeOf("open_screen");
		public static final StreamCodec<ByteBuf, OpenScreen> CODEC = StreamCodec.composite(ByteBufCodecs.stringUtf8(MAX_S2C_JSON), OpenScreen::json, OpenScreen::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Answer to an {@link Action}. {@code ok=false} carries an error translation key in json.error. */
	public record Response(long requestId, String channel, boolean ok, String json) implements CustomPacketPayload {
		public static final Type<Response> TYPE = typeOf("response");
		public static final StreamCodec<ByteBuf, Response> CODEC = StreamCodec.composite(
			ByteBufCodecs.LONG, Response::requestId,
			ByteBufCodecs.stringUtf8(64), Response::channel,
			ByteBufCodecs.BOOL, Response::ok,
			ByteBufCodecs.stringUtf8(MAX_S2C_JSON), Response::json,
			Response::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	/** Unsolicited event: mob sale offer, background round settlement, toast. */
	public record Notify(String kind, String json) implements CustomPacketPayload {
		public static final Type<Notify> TYPE = typeOf("notify");
		public static final StreamCodec<ByteBuf, Notify> CODEC = StreamCodec.composite(
			ByteBufCodecs.stringUtf8(32), Notify::kind, ByteBufCodecs.stringUtf8(65536), Notify::json, Notify::new);

		@Override
		public Type<? extends CustomPacketPayload> type() { return TYPE; }
	}

	public static void registerPayloads() {
		PayloadTypeRegistry.serverboundPlay().register(SelectTablet.TYPE, SelectTablet.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(OpenTablet.TYPE, OpenTablet.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(TabletClosed.TYPE, TabletClosed.CODEC);
		PayloadTypeRegistry.serverboundPlay().register(Action.TYPE, Action.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(Balance.TYPE, Balance.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TabletSync.TYPE, TabletSync.CODEC);
		PayloadTypeRegistry.clientboundPlay().register(TabletHolder.TYPE, TabletHolder.CODEC);
		PayloadTypeRegistry.clientboundPlay().registerLarge(OpenScreen.TYPE, OpenScreen.CODEC, MAX_S2C_JSON * 3 + 64);
		PayloadTypeRegistry.clientboundPlay().registerLarge(Response.TYPE, Response.CODEC, MAX_S2C_JSON * 3 + 256);
		PayloadTypeRegistry.clientboundPlay().register(Notify.TYPE, Notify.CODEC);
	}
}
