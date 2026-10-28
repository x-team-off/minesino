package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/**
 * Blackjack vs dealer. The hand is held by the server; this page shows the cards it sends.
 * Closing the tablet mid-hand stands automatically (server side).
 */
public class BlackjackPage extends GamePage {
	private final List<Integer> player = new ArrayList<>();
	private final List<Integer> dealer = new ArrayList<>();
	private final List<Long> dealt = new ArrayList<>();
	private boolean active, hidden;
	private int playerTotal, dealerTotal;
	private String outcome;
	private Button hit, stand;

	public BlackjackPage() {
		super("blackjack");
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float bw2 = (w - 4) / 2;
		hit = add(Button.of(Ui.tr("minesino.blackjack.hit"), Button.Style.PRIMARY, () -> act("hit")));
		hit.when(() -> active && !busy).bounds(x, y, bw2, 18);
		stand = add(Button.of(Ui.tr("minesino.blackjack.stand"), Button.Style.GHOST, () -> act("stand")));
		stand.when(() -> active && !busy).bounds(x + bw2 + 4, y, bw2, 18);
		return y + 23;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {}

	@Override
	public void onShow() {
		super.onShow();
		ClientNet.send("game:blackjack", "state", new JsonObject(), r -> {
			if (r.ok() && "active".equals(Json.getString(r.data(), "status", ""))) apply(r.data(), false);
		});
	}

	@Override
	protected boolean canPlay() {
		return !active && super.canPlay();
	}

	@Override
	protected String playLabel() {
		return busy ? "…" : Ui.tr("minesino.blackjack.deal");
	}

	@Override
	protected void onPlay() {
		outcome = null;
		player.clear();
		dealer.clear();
		dealt.clear();
		play("start", new JsonObject());
	}

	private void act(String action) {
		request("game:blackjack", action, new JsonObject(), r -> {
			if (r.ok()) apply(r.data(), true);
		});
	}

	@Override
	protected void onResult(JsonObject data) {
		apply(data, true);
	}

	private void apply(JsonObject d, boolean animate) {
		int before = player.size() + dealer.size();
		player.clear();
		dealer.clear();
		for (JsonElement e : d.getAsJsonArray("playerCards")) player.add(e.getAsInt());
		for (JsonElement e : d.getAsJsonArray("dealerCards")) dealer.add(e.getAsInt());
		hidden = Json.getBool(d, "dealerHidden", false);
		playerTotal = Json.getInt(d, "playerTotal", 0);
		dealerTotal = Json.getInt(d, "dealerTotal", 0);
		boolean done = "done".equals(Json.getString(d, "status", ""));
		active = !done;
		long now = Anim.now();
		int total = player.size() + dealer.size() + (hidden ? 1 : 0);
		while (dealt.size() < total) {
			int idx = dealt.size();
			dealt.add(animate && idx >= before ? now + (idx - before) * 220L : 0L);
		}
		if (animate && total > before) Ui.sound(SoundEvents.BOOK_PAGE_TURN, 1.2f, 0.7f);
		lockInputs(active);
		if (done) {
			result = d;
			if (d.has("recent")) recent = d.getAsJsonArray("recent");
			outcome = Json.getString(d, "result", "");
			animating = true;
			animStart = now + Math.max(0, (total - before)) * 220L - 600;
		} else {
			held = null;
		}
	}

	@Override
	protected long animationMs() {
		return 900;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		// felt
		Ui.round(g, bx + 6, by + 6, bw - 12, bh - 12, 30, 0xFF0B3B2A);
		Ui.gradient(g, bx + 6, by + 6, bw - 12, bh * 0.5f, 0x33FFFFFF, 0x00000000);
		Ui.center(g, Ui.tr("minesino.blackjack.rules"), bx + bw / 2, by + bh / 2 - 4, Theme.alpha(Theme.GOLD, 0.55f));
		float cw = Math.min(30, bw / 8), ch = cw * 1.4f;
		int index = 0;
		// dealer row
		Ui.text(g, Ui.tr("minesino.blackjack.dealer") + (dealer.isEmpty() ? "" : "  " + dealerTotal + (hidden ? "+" : "")),
			bx + 14, by + 14, Theme.TEXT_DIM);
		List<Integer> dealerShown = new ArrayList<>(dealer);
		if (hidden) dealerShown.add(-1);
		index = row(g, dealerShown, bx + 14, by + 26, cw, ch, index, false);
		// player row
		float py = by + bh - ch - 26;
		Ui.text(g, Ui.tr("minesino.blackjack.you") + (player.isEmpty() ? "" : "  " + playerTotal), bx + 14, py - 12, Theme.TEXT);
		row(g, player, bx + 14, py, cw, ch, index, true);
		if (outcome != null && !animating) {
			int c = outcome.equals("win") || outcome.equals("blackjack") || outcome.equals("dealer_bust") ? Theme.GOLD
				: outcome.equals("push") ? Theme.TEXT_DIM : Theme.RED;
			Ui.right(g, Ui.tr("minesino.blackjack.result." + outcome), bx + bw - 14, py - 12, c);
		}
		if (player.isEmpty() && !busy) Ui.center(g, Ui.tr("minesino.blackjack.hint"), bx + bw / 2, by + bh / 2 + 8, Theme.MUTED);
	}

	private int row(GuiGraphicsExtractor g, List<Integer> cards, float x, float y, float cw, float ch, int index, boolean playerRow) {
		for (int i = 0; i < cards.size(); i++) {
			int dealIdx = playerRow ? dealer.size() + (hidden ? 1 : 0) + i : i;
			long start = dealIdx < dealt.size() ? dealt.get(dealIdx) : 0;
			float t = start == 0 ? 1 : Anim.easeOutCubic(Anim.progress(start, 300));
			if (start != 0 && Anim.now() < start) continue;
			float cx = x + i * (cw * 0.72f);
			float fromX = bx + bw - cw - 10, fromY = by + 10;
			float dx = fromX + (cx - fromX) * t, dy = fromY + (y - fromY) * t;
			CardPainter.draw(g, cards.get(i), dx, dy, cw, ch, cards.get(i) < 0 ? 0 : 0.5f + 0.5f * t);
		}
		return index + cards.size();
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.blackjack.result." + Json.getString(o, "result", "loss"));
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		Ui.text(g, Ui.fit(Ui.tr("minesino.blackjack.payout", Json.getInt(info, "naturalNum", 3) + ":" + Json.getInt(info, "naturalDen", 2)), w), x, y, Theme.MUTED);
	}

	@Override
	protected String historyChip(JsonObject o) {
		return Json.getInt(o, "player", 0) + ":" + Json.getInt(o, "dealer", 0);
	}
}
