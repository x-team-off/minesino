package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/** Dice: choose 1..5 faces (a number or a range); probability and multiplier are shown before rolling. */
public class DicePage extends GamePage {
	private final boolean[] faces = new boolean[7];
	private int roll = 6;
	private float faceY, faceSize;

	public DicePage() {
		super("dice");
		faces[4] = faces[5] = faces[6] = true;
	}

	private int k() {
		int k = 0;
		for (int i = 1; i <= 6; i++) if (faces[i]) k++;
		return k;
	}

	private void set(int... f) {
		java.util.Arrays.fill(faces, false);
		for (int i : f) faces[i] = true;
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		String[] labels = {"1–3", "4–6", Ui.tr("minesino.dice.even"), Ui.tr("minesino.dice.odd")};
		int[][] sets = {{1, 2, 3}, {4, 5, 6}, {2, 4, 6}, {1, 3, 5}};
		float cw = (w - 12) / 4;
		for (int i = 0; i < 4; i++) {
			int[] s = sets[i];
			add(Button.of(labels[i], Button.Style.CHIP, () -> set(s)).when(() -> !animating)).bounds(x + i * (cw + 4), y, cw, 14);
		}
		return y + 19;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		faceSize = Math.min((w - 40) / 6f, 34);
		faceY = y + h - faceSize - 26;
	}

	@Override
	protected String stakeProblem() {
		int k = k();
		if (k < 1 || k > 5) return "minesino.dice.pick";
		return super.stakeProblem();
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.dice.roll");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		JsonArray a = new JsonArray();
		for (int i = 1; i <= 6; i++) if (faces[i]) a.add(i);
		p.add("faces", a);
		play("roll", p);
	}

	@Override
	protected long animationMs() {
		return 1400;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		Ui.sound(SoundEvents.BONE_BLOCK_PLACE, 1.4f, 0.6f);
	}

	@Override
	protected void finishAnimation() {
		if (result != null) roll = Json.getInt(result, "roll", roll);
		super.finishAnimation();
	}

	private float faceX(int i) {
		float total = faceSize * 6 + 6 * 5;
		return bx + (bw - total) / 2 + (i - 1) * (faceSize + 6);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (animating || button != Ui.LEFT) return false;
		for (int i = 1; i <= 6; i++) {
			if (Ui.inside(mx, my, faceX(i), faceY, faceSize, faceSize)) {
				if (faces[i] || k() < 5) faces[i] = !faces[i];
				Ui.clickSound();
				return true;
			}
		}
		return false;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float cx = bx + bw / 2, cy = by + (faceY - by) / 2;
		float big = Math.min(54, (faceY - by) * 0.55f);
		int face = roll;
		float rot = 0, jump = 0;
		if (animating) {
			float t = Anim.progress(animStart, animationMs());
			if (t < 0.85f) {
				face = 1 + (int) ((Anim.now() / 70) % 6);
				rot = (1 - t) * 9f;
				jump = (float) Math.abs(Math.sin(t * Math.PI * 3)) * big * 0.4f * (1 - t);
			} else {
				face = result == null ? roll : Json.getInt(result, "roll", roll);
			}
		}
		boolean done = !animating && result != null;
		boolean win = done && Json.getBool(result, "win", false);
		int border = done ? (win ? Theme.GOLD : Theme.RED) : Theme.BORDER_HI;
		if (done) Ui.glow(g, cx - big / 2, cy - big / 2, big, big, 10, Theme.alpha(win ? Theme.GOLD : Theme.RED, 0.4f));
		DicePainter.draw(g, face, cx, cy - jump, big, rot, 0xFFFAF7F0, 0xFF1A1213, border);
		for (int i = 1; i <= 6; i++) {
			float fx = faceX(i);
			boolean hov = !animating && Ui.inside(mx, my, fx, faceY, faceSize, faceSize);
			if (faces[i]) Ui.glow(g, fx, faceY, faceSize, faceSize, 4, Theme.alpha(Theme.NEON, 0.5f));
			DicePainter.draw(g, i, fx + faceSize / 2, faceY + faceSize / 2 - (hov ? 1 : 0), faceSize, 0,
				faces[i] ? 0xFF0E3446 : Theme.CARD, faces[i] ? Theme.NEON : Theme.MUTED, faces[i] ? Theme.NEON : Theme.BORDER);
		}
		Ui.center(g, Ui.tr("minesino.dice.hint"), bx + bw / 2, faceY + faceSize + 8, Theme.MUTED);
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		int k = k();
		if (k < 1 || k > 5) return;
		long edge = Json.getLong(info, "edgeBps", 200);
		long multBps = 6L * (10_000L - edge) / k;
		Ui.text(g, Ui.tr("minesino.ui.chance", String.format(java.util.Locale.ROOT, "%.2f%%", k * 100.0 / 6)), x, y, Theme.MUTED);
		Ui.right(g, "×" + mult(multBps / 100), x + w, y, Theme.GOLD);
		if (bet != null && bet.bet() > 0) {
			Ui.text(g, Ui.tr("minesino.ui.potential", ClientState.money(bet.bet() * multBps / 10_000)), x, y + 10, Theme.TEXT_DIM);
		}
	}

	@Override
	protected float recentTop() {
		return sideY + 24;
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.dice.rolled", Json.getInt(o, "roll", 0));
	}

	@Override
	protected String historyChip(JsonObject o) {
		return Integer.toString(Json.getInt(o, "roll", 0));
	}
}
