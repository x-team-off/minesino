package dev.minesino.client.gui.games;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/** Hi-Lo: guess whether the next card is higher-or-same or lower-or-same; cash out any time. */
public class HiLoPage extends GamePage {
	private final List<Integer> cards = new ArrayList<>();
	private boolean active;
	private int card = 7;
	private long cashout, multHundredths = 100;
	private int hiChance, loChance;
	private long hiMult, loMult;
	private long lastCardAt;

	public HiLoPage() {
		super("hilo");
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float cw = (w - 4) / 2;
		add(new Button(() -> Ui.tr("minesino.hilo.higher") + (hiMult > 0 ? " ×" + mult(hiMult) : ""), Button.Style.PRIMARY, () -> guess("higher"))
			.when(() -> active && !busy && hiChance < 13)).bounds(x, y, cw, 18);
		add(new Button(() -> Ui.tr("minesino.hilo.lower") + (loMult > 0 ? " ×" + mult(loMult) : ""), Button.Style.PRIMARY, () -> guess("lower"))
			.when(() -> active && !busy && loChance < 13)).bounds(x + cw + 4, y, cw, 18);
		add(new Button(() -> Ui.tr("minesino.mines.cashout", ClientState.money(cashout)), Button.Style.SUCCESS, () -> act("cashout")))
			.when(() -> active && !busy && cashout > 0).bounds(x, y + 22, w, 18);
		return y + 45;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {}

	@Override
	public void onShow() {
		super.onShow();
		ClientNet.send("game:hilo", "state", new JsonObject(), r -> {
			if (r.ok() && "active".equals(Json.getString(r.data(), "status", ""))) apply(r.data());
		});
	}

	@Override
	protected boolean canPlay() {
		return !active && super.canPlay();
	}

	@Override
	protected String playLabel() {
		return busy ? "…" : Ui.tr("minesino.hilo.start");
	}

	@Override
	protected void onPlay() {
		cards.clear();
		play("start", new JsonObject());
	}

	private void guess(String dir) {
		act(dir);
	}

	private void act(String action) {
		request("game:hilo", action, new JsonObject(), r -> {
			if (r.ok()) apply(r.data());
		});
	}

	@Override
	protected void onResult(JsonObject data) {
		apply(data);
	}

	private void apply(JsonObject d) {
		int before = cards.size();
		cards.clear();
		for (JsonElement e : d.getAsJsonArray("cards")) cards.add(e.getAsInt());
		if (cards.size() != before) {
			lastCardAt = Anim.now();
			Ui.sound(SoundEvents.BOOK_PAGE_TURN, 1.3f, 0.7f);
		}
		card = Json.getInt(d, "card", 7);
		cashout = Json.getLong(d, "cashout", 0);
		multHundredths = Json.getLong(d, "mult", 100);
		hiChance = Json.getInt(d, "higherChance", 0);
		loChance = Json.getInt(d, "lowerChance", 0);
		hiMult = Json.getLong(d, "higherMult", 0);
		loMult = Json.getLong(d, "lowerMult", 0);
		active = "active".equals(Json.getString(d, "status", ""));
		lockInputs(active);
		if (active) {
			held = null;
		} else {
			result = d;
			if (d.has("recent")) recent = d.getAsJsonArray("recent");
			animating = true;
			animStart = Anim.now();
		}
	}

	@Override
	protected long animationMs() {
		return 600;
	}

	/** Suit is cosmetic (infinite deck); rank 1..13 maps onto card ids 0..12. */
	private static int cardId(int rank, int index) {
		return (index % 4) * 13 + (rank - 1);
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float cw = Math.min(46, bw * 0.18f), ch = cw * 1.4f;
		float cx = bx + bw / 2, cy = by + bh / 2 - 16;
		if (cards.isEmpty()) {
			CardPainter.draw(g, -1, cx - cw / 2, cy - ch / 2, cw, ch, 0);
			Ui.center(g, Ui.tr("minesino.hilo.hint"), cx, cy + ch / 2 + 10, Theme.MUTED);
			return;
		}
		float t = Anim.easeOutCubic(Anim.progress(lastCardAt, 300));
		CardPainter.draw(g, cardId(card, cards.size() - 1), cx - cw / 2, cy - ch / 2, cw, ch, 0.5f + 0.5f * t);
		// trail of previous cards
		float sw2 = cw * 0.45f, sh2 = sw2 * 1.4f;
		int n = Math.min(10, cards.size() - 1);
		for (int i = 0; i < n; i++) {
			int idx = cards.size() - 2 - i;
			CardPainter.draw(g, cardId(cards.get(idx), idx), cx - cw / 2 - 10 - (i + 1) * (sw2 + 3), cy - sh2 / 2 + ch / 2 - sh2, sw2, sh2, 1);
		}
		Ui.center(g, Ui.tr("minesino.hilo.streak", cards.size() - 1, mult(multHundredths)), cx, cy + ch / 2 + 10, Theme.GOLD);
		if (active) {
			Ui.center(g, Ui.tr("minesino.hilo.odds", hiChance * 100 / 13, loChance * 100 / 13), cx, cy + ch / 2 + 22, Theme.TEXT_DIM);
		}
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.hilo.recent", Json.getInt(o, "steps", 0));
	}
}
