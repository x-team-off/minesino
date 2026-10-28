package dev.minesino.client.gui.games;

import com.google.gson.JsonObject;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.RouletteWheelTexture;
import dev.minesino.client.gui.TabletData;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/** Wheel of fortune: 30 segments with multipliers; the wheel stops on the server's segment. */
public class WheelPage extends GamePage {
	private long[] segments = new long[0];
	private Identifier tex;
	private float angle, from, to;
	private int lastSeg = -1, tickSeg = -1;
	private float cx, cy, r;

	public WheelPage() {
		super("wheel");
	}

	static int colorFor(long m) {
		if (m == 0) return 0xFF2B1B1D;
		if (m <= 100) return 0xFF475569;
		if (m <= 120) return 0xFF0E7490;
		if (m <= 150) return 0xFFE0102E;
		if (m <= 200) return 0xFFDB2777;
		if (m <= 300) return 0xFF16A34A;
		return 0xFFD4A017;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		segments = info.has("segments") ? TabletData.longs(info.getAsJsonArray("segments")) : new long[] {0, 200};
		int[] colors = new int[segments.length];
		for (int i = 0; i < segments.length; i++) colors[i] = colorFor(segments[i]);
		tex = RouletteWheelTexture.get("fortune" + segments.length, colors);
		r = Math.min(w, h - 30) / 2f - 8;
		cx = x + w / 2;
		cy = y + 8 + r + 6;
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.wheel.spin");
	}

	@Override
	protected void onPlay() {
		play("spin", new JsonObject());
	}

	@Override
	protected long animationMs() {
		return 3800;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		int seg = Json.getInt(data, "segment", 0);
		float two = (float) (Math.PI * 2);
		from = angle % two;
		float desired = -seg * two / segments.length;
		float base = desired - two * 4;
		while (base > from - two * 3) base -= two;
		to = base;
	}

	@Override
	protected void finishAnimation() {
		angle = to;
		lastSeg = result == null ? -1 : Json.getInt(result, "segment", -1);
		super.finishAnimation();
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (animating) {
			float t = Anim.easeOutCubic(Anim.progress(animStart, animationMs()));
			angle = from + (to - from) * t;
			int seg = Math.floorMod(Math.round(-angle / (float) (Math.PI * 2) * segments.length), segments.length);
			if (seg != tickSeg) {
				tickSeg = seg;
				Ui.sound(SoundEvents.NOTE_BLOCK_HAT.value(), 1.8f, 0.25f);
			}
		}
		Ui.circleGlow(g, cx, cy, r, 12, Theme.alpha(Theme.GOLD, 0.35f));
		RouletteWheelTexture.draw(g, tex, cx, cy, r, angle);
		for (int i = 0; i < segments.length; i++) {
			g.pose().pushMatrix();
			g.pose().translate(cx, cy);
			g.pose().rotate(angle + i * (float) (Math.PI * 2 / segments.length));
			String s = segments[i] == 0 ? "0" : mult(segments[i]);
			Ui.scaledCenter(g, s, 0, -r * 0.8f, Math.max(0.4f, r / 120f), 0xFFF5F5F5, false);
			g.pose().popMatrix();
		}
		Ui.circle(g, cx, cy, r * 0.2f, Theme.SURFACE);
		Ui.logo(g, cx - Ui.width("MineSino") * 0.35f, cy - 3, 0.7f);
		// pointer
		Ui.round(g, cx - 2, cy - r - 6, 4, 11, 2, Theme.TEXT);
		Ui.circle(g, cx, cy - r - 6, 3.5f, Theme.GOLD);
		if (lastSeg >= 0 && !animating) {
			Ui.center(g, "×" + mult(segments[lastSeg]), cx, cy + r + 8, segments[lastSeg] >= 100 ? Theme.GOLD : Theme.RED);
		}
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		Ui.text(g, Ui.fit(Ui.tr("minesino.wheel.rules"), w), x, y, Theme.MUTED);
	}
}
