package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.BetPanel;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/**
 * Common layout for games: the board on the left, the stake panel, game options, the action button
 * and the last rounds on the right. The server result arrives before the animation starts; the
 * page only replays it. While the animation runs the header keeps showing the pre-result balance.
 */
public abstract class GamePage extends Page {
	protected final String gameId;
	protected JsonObject info;
	protected BetPanel bet;
	protected Button playButton;
	protected JsonArray recent = new JsonArray();
	/** Balance shown in the header until the current animation finishes. */
	protected Long held;
	protected boolean animating;
	protected long animStart;
	protected JsonObject result;
	private long bannerAt;
	private long bannerNet;
	private long bannerPayout;
	private boolean bannerPush;
	protected float bx, by, bw, bh;
	protected float sx, sw;
	protected float sideY;

	protected GamePage(String gameId) {
		this.gameId = gameId;
	}

	@Override
	public String title() {
		return Ui.tr("minesino.game." + gameId);
	}

	public String gameId() {
		return gameId;
	}

	/** A result pushed by the server without a request (crash explosion, auto cash-out). */
	public void onServerEvent(JsonObject data) {}

	@Override
	protected final void layout() {
		info = screen.data.game(gameId);
		sw = Math.clamp(w * 0.34f, 128, 168);
		sx = x + w - sw;
		bx = x;
		by = y;
		bw = w - sw - 8;
		bh = h;
		float cy = y + 12;
		if (usesBetPanel()) {
			bet = add(new BetPanel(gameId, screen.data.minBet, screen.data.maxBet, screen.data.quickBets));
			bet.bounds(sx, cy, sw, 51);
			cy += 57;
		}
		cy = layoutSide(sx, cy, sw);
		playButton = add(new Button(this::playLabel, Button.Style.GOLD, this::onPlay));
		playButton.when(this::canPlay);
		playButton.bounds(sx, cy, sw, 20);
		sideY = cy + 26;
		layoutBoard(bx, by, bw, bh);
	}

	protected boolean usesBetPanel() {
		return true;
	}

	/** Adds game options below the stake panel; returns the next free y. */
	protected float layoutSide(float x, float y, float w) {
		return y;
	}

	protected abstract void layoutBoard(float x, float y, float w, float h);

	protected abstract void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt);

	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.ui.play");
	}

	protected boolean canPlay() {
		return !busy && !animating && (bet == null || stakeProblem() == null);
	}

	/** Total money this round will charge (roulette sums several chips). */
	protected long currentStake() {
		return bet == null ? 0 : bet.bet();
	}

	protected String stakeProblem() {
		return bet == null ? null : bet.problem();
	}

	protected abstract void onPlay();

	/** Animation length for the current result in ms (before speed setting). */
	protected long animationMs() {
		return 1500;
	}

	@Override
	public void onShow() {
		JsonObject p = new JsonObject();
		p.addProperty("game", gameId);
		ClientNet.send("data", "recent", p, r -> {
			if (r.ok() && r.data().has("recent")) recent = r.data().getAsJsonArray("recent");
		});
	}

	/** Sends a round action with the current stake. */
	protected void play(String action, JsonObject params) {
		if (bet != null) {
			String problem = stakeProblem();
			if (problem != null) {
				showError(problem);
				return;
			}
			if (!params.has("bet")) params.addProperty("bet", bet.bet());
			held = ClientState.balance - currentStake();
		}
		lockInputs(true);
		request("game:" + gameId, action, params, reply -> {
			if (!reply.ok()) {
				held = null;
				lockInputs(false);
				return;
			}
			onResult(reply.data());
		});
	}

	/** Default handling of a one-shot result: start the animation. */
	protected void onResult(JsonObject data) {
		result = data;
		if (data.has("recent")) recent = data.getAsJsonArray("recent");
		animating = true;
		animStart = Anim.now();
		startAnimation(data);
	}

	protected void startAnimation(JsonObject data) {}

	@Override
	public void tick() {
		super.tick();
		if (animating && Anim.progress(animStart, animationMs()) >= 1f) finishAnimation();
	}

	/** Reveals the outcome: releases the held balance and shows the win/loss banner. */
	protected void finishAnimation() {
		animating = false;
		held = null;
		lockInputs(false);
		if (result != null && result.has("payout")) {
			long payout = Json.getLong(result, "payout", 0), stake = Json.getLong(result, "bet", 0);
			bannerPayout = payout;
			showBanner(payout - stake, payout == stake);
		}
	}

	protected void showBanner(long net, boolean push) {
		bannerAt = Anim.now();
		bannerNet = net;
		bannerPush = push;
		if (net > 0) Ui.sound(SoundEvents.PLAYER_LEVELUP, 1.4f, 0.5f);
		else if (!push) Ui.sound(SoundEvents.NOTE_BLOCK_BASS.value(), 0.6f, 0.5f);
	}

	protected void lockInputs(boolean locked) {
		if (bet != null) bet.locked = locked;
	}

	@Override
	public Long heldBalance() {
		return held;
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		// board surface
		Ui.panel(g, bx, by, bw, bh, 8, 0xFF120F1A, Theme.BORDER);
		Ui.gradient(g, bx + 1, by + 1, bw - 2, bh * 0.4f, Theme.alpha(dev.minesino.client.gui.Icons.accent(gameId), 0.07f), 0x00000000);
		g.enableScissor((int) bx, (int) by, (int) (bx + bw), (int) (by + bh));
		renderBoard(g, mx, my, dt);
		g.disableScissor();
		// side panel captions
		if (bet != null) Ui.text(g, Ui.tr("minesino.ui.stake"), sx, y + 2, Theme.MUTED);
		renderSideInfo(g, sx, sideY, sw);
		renderRecent(g);
		renderBanner(g);
	}

	/** Extra text under the play button: potential win, odds... */
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {}

	protected float recentTop() {
		return sideY + 22;
	}

	protected void renderRecent(GuiGraphicsExtractor g) {
		float ry = recentTop();
		if (ry > y + h - 24) return;
		Ui.text(g, Ui.tr("minesino.ui.recent"), sx, ry, Theme.MUTED);
		ry += 11;
		int shown = 0;
		for (JsonElement e : recent) {
			if (ry + 11 > y + h) break;
			JsonObject o = e.getAsJsonObject();
			long payout = Json.getLong(o, "payout", 0), stake = Json.getLong(o, "bet", 0);
			long net = payout - stake;
			int color = net > 0 ? Theme.GREEN : net == 0 ? Theme.TEXT_DIM : Theme.RED;
			Ui.round(g, sx, ry, sw, 10, 3, shown % 2 == 0 ? 0xFF17131F : 0xFF1B1725);
			Ui.text(g, Ui.fit(recentLabel(o), sw * 0.55f), sx + 4, ry + 1, Theme.TEXT_DIM);
			Ui.right(g, ClientState.moneySigned(net), sx + sw - 4, ry + 1, color);
			ry += 11;
			shown++;
		}
		if (shown == 0) Ui.text(g, Ui.tr("minesino.ui.no_rounds"), sx, ry, Theme.alpha(Theme.MUTED, 0.7f));
	}

	/** Short description of a past round, e.g. the multiplier. */
	protected String recentLabel(JsonObject o) {
		long payout = Json.getLong(o, "payout", 0), stake = Math.max(1, Json.getLong(o, "bet", 1));
		return "×" + mult(payout * 100 / stake);
	}

	public static String mult(long hundredths) {
		long w = hundredths / 100, f = hundredths % 100;
		return f == 0 ? Long.toString(w) : w + "." + (f < 10 ? "0" + f : Long.toString(f)).replaceAll("0$", "");
	}

	private void renderBanner(GuiGraphicsExtractor g) {
		if (bannerAt == 0) return;
		float t = (Anim.now() - bannerAt) / 2600f;
		if (t > 1) {
			bannerAt = 0;
			return;
		}
		float in = Anim.easeOutBack(Math.min(1, t * 5));
		float a = t > 0.8f ? (1 - t) / 0.2f : 1;
		boolean win = bannerNet > 0;
		boolean partial = !win && !bannerPush && bannerPayout > 0;
		String head = partial ? Ui.tr("minesino.result.partial", ClientState.money(bannerPayout))
			: Ui.tr(win ? "minesino.result.win" : bannerPush ? "minesino.result.push" : "minesino.result.loss");
		String amount = bannerPush ? "±0" : ClientState.moneySigned(bannerNet);
		int color = win ? Theme.GOLD : bannerPush ? Theme.TEXT_DIM : partial ? 0xFFF59E0B : Theme.RED;
		float cw = Math.max(Ui.width(head), Ui.width(amount) * 1.6f) + 30;
		float ch = 40;
		float cx = bx + bw / 2, cy = by + bh / 2;
		g.pose().pushMatrix();
		g.pose().translate(cx, cy);
		g.pose().scale(0.6f + 0.4f * in, 0.6f + 0.4f * in);
		Ui.glow(g, -cw / 2, -ch / 2, cw, ch, 14, Theme.alpha(color, 0.45f * a));
		Ui.panel(g, -cw / 2, -ch / 2, cw, ch, 9, Theme.alpha(0xF0120E1A, a), Theme.alpha(color, a));
		Ui.center(g, head, 0, -ch / 2 + 7, Theme.alpha(Theme.TEXT, a));
		Ui.scaledCenter(g, amount, 0, -ch / 2 + 19, 1.6f, Theme.alpha(color, a), true);
		g.pose().popMatrix();
	}
}
