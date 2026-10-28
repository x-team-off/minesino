package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/** Keno: pick up to 10 of 40 numbers; 10 are drawn one by one. */
public class KenoPage extends GamePage {
	private final boolean[] picked = new boolean[41];
	private final List<Integer> drawn = new ArrayList<>();
	private float gx, gy, cell;
	private int lastSoundIdx = -1;

	public KenoPage() {
		super("keno");
	}

	private int picks() {
		int k = 0;
		for (boolean b : picked) if (b) k++;
		return k;
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float cw = (w - 4) / 2;
		add(Button.of(Ui.tr("minesino.keno.random"), Button.Style.GHOST, () -> {
			java.util.Arrays.fill(picked, false);
			Random r = new Random();
			int n = 0;
			while (n < 8) {
				int v = 1 + r.nextInt(40);
				if (!picked[v]) {
					picked[v] = true;
					n++;
				}
			}
		}).when(() -> !animating)).bounds(x, y, cw, 15);
		add(Button.of(Ui.tr("minesino.roulette.clear"), Button.Style.GHOST, () -> java.util.Arrays.fill(picked, false))
			.when(() -> !animating)).bounds(x + cw + 4, y, cw, 15);
		return y + 20;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		cell = Math.min((w - 16) / 8f, (h - 50) / 5f);
		gx = x + (w - cell * 8) / 2;
		gy = y + 10;
	}

	@Override
	protected String stakeProblem() {
		int k = picks();
		if (k < 1 || k > 10) return "minesino.keno.pick";
		return super.stakeProblem();
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.keno.draw");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		JsonArray a = new JsonArray();
		for (int i = 1; i <= 40; i++) if (picked[i]) a.add(i);
		p.add("picks", a);
		drawn.clear();
		play("draw", p);
	}

	@Override
	protected long animationMs() {
		return 10 * 180 + 300;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		drawn.clear();
		for (JsonElement e : data.getAsJsonArray("drawn")) drawn.add(e.getAsInt());
		lastSoundIdx = -1;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (animating || button != Ui.LEFT) return false;
		int c = (int) ((mx - gx) / cell), r = (int) ((my - gy) / cell);
		if (mx < gx || my < gy || c > 7 || r > 4) return false;
		int n = r * 8 + c + 1;
		if (picked[n] || picks() < 10) picked[n] = !picked[n];
		if (!animating && result != null) drawn.clear();
		Ui.clickSound();
		return true;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		int visible = drawn.size();
		if (animating) {
			visible = Math.min(drawn.size(), (int) (Anim.progress(animStart, animationMs()) * animationMs() / 180));
			if (visible - 1 != lastSoundIdx && visible > 0) {
				lastSoundIdx = visible - 1;
				boolean hit = picked[drawn.get(visible - 1)];
				Ui.sound(hit ? SoundEvents.AMETHYST_BLOCK_CHIME : SoundEvents.NOTE_BLOCK_HAT.value(), hit ? 1.4f : 1.0f, 0.5f);
			}
		}
		boolean[] isDrawn = new boolean[41];
		for (int i = 0; i < visible; i++) isDrawn[drawn.get(i)] = true;
		int hits = 0;
		for (int n = 1; n <= 40; n++) {
			int c = (n - 1) % 8, r = (n - 1) / 8;
			float x0 = gx + c * cell + 1.5f, y0 = gy + r * cell + 1.5f, s = cell - 3;
			boolean p = picked[n], d = isDrawn[n];
			if (p && d) hits++;
			int fill = p && d ? 0xFF15803D : d ? 0xFF5C0A16 : p ? 0xFFE0102E : Theme.CARD;
			int border = p && d ? Theme.GOLD : p ? Theme.ACCENT : d ? Theme.ACCENT_DARK : Theme.BORDER;
			boolean hov = !animating && Ui.inside(mx, my, x0, y0, s, s);
			if (p && d) Ui.glow(g, x0, y0, s, s, 4, Theme.alpha(Theme.GOLD, 0.5f));
			Ui.panel(g, x0, y0 - (hov ? 1 : 0), s, s, s / 2, hov ? Theme.lerp(fill, 0xFFFFFFFF, 0.1f) : fill, border);
			Ui.center(g, Integer.toString(n), x0 + s / 2, y0 + s / 2 - 4, p || d ? Theme.TEXT : Theme.TEXT_DIM);
		}
		float ty = gy + cell * 5 + 8;
		Ui.text(g, Ui.tr("minesino.keno.status", picks(), hits), bx + 8, ty, Theme.TEXT_DIM);
		// paytable row for the current number of picks
		int k = picks();
		if (k >= 1 && k <= 10 && info.has("paytable")) {
			JsonArray row = info.getAsJsonArray("paytable").get(k - 1).getAsJsonArray();
			float x = bx + 8;
			float py = ty + 12;
			for (int h2 = 0; h2 < row.size(); h2++) {
				long m = row.get(h2).getAsLong();
				if (m == 0) continue;
				String s = h2 + ": ×" + mult(m);
				if (x + Ui.width(s) > bx + bw - 6) break;
				Ui.text(g, s, x, py, hits == h2 && !animating && result != null ? Theme.GOLD : Theme.MUTED);
				x += Ui.width(s) + 8;
			}
		}
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.keno.recent", Json.getInt(o, "hits", 0), Json.getInt(o, "picks", 0));
	}

	@Override
	protected String historyChip(JsonObject o) {
		return Json.getInt(o, "hits", 0) + "/" + Json.getInt(o, "picks", 0);
	}
}
