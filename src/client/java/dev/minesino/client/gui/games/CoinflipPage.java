package dev.minesino.client.gui.games;

import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/** Coinflip: pick heads or tails; the coin flips to the server result. */
public class CoinflipPage extends GamePage {
	private String side = "heads";
	private String shown = "heads";

	public CoinflipPage() {
		super("coinflip");
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float cw = (w - 4) / 2;
		add(new Button(() -> Ui.tr("minesino.coin.heads"), Button.Style.GHOST, () -> side = "heads").select(() -> side.equals("heads"))
			.when(() -> !animating)).bounds(x, y, cw, 16);
		add(new Button(() -> Ui.tr("minesino.coin.tails"), Button.Style.GHOST, () -> side = "tails").select(() -> side.equals("tails"))
			.when(() -> !animating)).bounds(x + cw + 4, y, cw, 16);
		return y + 21;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.coin.flip");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		p.addProperty("side", side);
		play("flip", p);
	}

	@Override
	protected long animationMs() {
		return 1800;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		Ui.sound(SoundEvents.CHAIN_PLACE, 1.6f, 0.6f);
	}

	@Override
	protected void finishAnimation() {
		if (result != null) shown = Json.getString(result, "result", shown);
		super.finishAnimation();
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float cx = bx + bw / 2, cy = by + bh / 2 - 6;
		float r = Math.min(bw, bh) * 0.24f;
		String face = shown;
		float squash = 1, lift = 0;
		if (animating) {
			float t = Anim.progress(animStart, animationMs());
			float flips = 9.5f + (result != null && !Json.getString(result, "result", "heads").equals(shown) ? 0.5f : 0);
			double ang = Anim.easeOutCubic(t) * flips * Math.PI;
			squash = (float) Math.abs(Math.cos(ang));
			boolean other = ((int) Math.floor(ang / Math.PI + 0.5)) % 2 == 1;
			face = other ? (shown.equals("heads") ? "tails" : "heads") : shown;
			lift = (float) Math.sin(t * Math.PI) * r * 0.6f;
		}
		Ui.glow(g, cx - r, cy + r + 4 - lift * 0.1f, r * 2, 4, 6, Theme.alpha(0xFF000000, 0.6f));
		g.pose().pushMatrix();
		g.pose().translate(cx, cy - lift);
		g.pose().scale(1, Math.max(0.05f, squash));
		boolean heads = face.equals("heads");
		int rim = heads ? Theme.GOLD_DARK : 0xFF8A8F98;
		int body = heads ? Theme.GOLD : 0xFFD1D5DB;
		Ui.circleGlow(g, 0, 0, r, 8, Theme.alpha(body, 0.45f));
		Ui.circle(g, 0, 0, r, rim);
		Ui.circle(g, 0, 0, r * 0.88f, body);
		Ui.ring(g, 0, 0, r * 0.72f, Theme.alpha(rim, 0.8f));
		String label = Ui.tr(heads ? "minesino.coin.heads_short" : "minesino.coin.tails_short");
		Ui.scaledCenter(g, label, 0, -r * 0.22f, r / 14f, heads ? 0xFF5A3A08 : 0xFF374151, false);
		g.pose().popMatrix();
		Ui.center(g, Ui.tr("minesino.coin.your_side", Ui.tr("minesino.coin." + side)), cx, by + bh - 18, Theme.TEXT_DIM);
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		long multBps = Json.getLong(info, "multBps", 19600);
		Ui.text(g, Ui.tr("minesino.ui.chance", "50%"), x, y, Theme.MUTED);
		Ui.right(g, "×" + mult(multBps / 100), x + w, y, Theme.GOLD);
		if (bet != null && bet.bet() > 0) Ui.text(g, Ui.tr("minesino.ui.potential", ClientState.money(bet.bet() * multBps / 10_000)), x, y + 10, Theme.TEXT_DIM);
	}

	@Override
	protected float recentTop() {
		return sideY + 24;
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.coin." + Json.getString(o, "result", "heads"));
	}
}
