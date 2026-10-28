package dev.minesino.client.gui.games;

import com.google.gson.JsonObject;
import dev.minesino.casino.games.CrashGame;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.client.gui.widget.TextField;
import dev.minesino.economy.Json;
import java.math.BigDecimal;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;

/**
 * Crash: launch, watch the multiplier grow and cash out at any moment before the explosion.
 * The server decides the cash-out multiplier from its own clock and keeps the crash point secret
 * until the round ends. An optional auto cash-out target settles the round automatically.
 */
public class CrashPage extends GamePage {
	private TextField autoField;
	private boolean autoOn;
	private boolean flying;
	private long flightStart;
	private long flightStake;
	private long finalCrash = -1;
	private long cashedAt;
	private boolean boom;

	public CrashPage() {
		super("crash");
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		add(new Button(() -> Ui.tr(autoOn ? "minesino.crash.auto_on" : "minesino.crash.auto_off"), Button.Style.CHIP, () -> autoOn = !autoOn)
			.select(() -> autoOn).when(() -> !flying && !busy)).bounds(x, y, w * 0.42f, 15);
		autoField = add(new TextField());
		autoField.allowed = c -> Character.isDigit(c) || c == '.' || c == ',';
		autoField.maxLength = 8;
		autoField.setText("2");
		autoField.suffix = "×";
		autoField.bounds(x + w * 0.42f + 4, y, w * 0.58f - 4, 15);
		long[] quick = {150, 200, 500, 1000};
		float cw = (w - 12) / 4;
		for (int i = 0; i < quick.length; i++) {
			long q = quick[i];
			add(new Button(() -> "×" + mult(q), Button.Style.CHIP, () -> {
				autoField.setText(mult(q));
				autoOn = true;
			}).select(() -> autoOn && autoHundredths() == q).when(() -> !flying)).bounds(x + i * (cw + 4), y + 19, cw, 13);
		}
		Button cash = add(new Button(this::cashLabel, Button.Style.SUCCESS, this::cashOut));
		cash.when(() -> flying && !busy).bounds(x, y + 36, w, 20);
		return y + 60;
	}

	private String cashLabel() {
		if (!flying) return Ui.tr("minesino.crash.cashout_idle");
		long m = liveMultiplier();
		return Ui.tr("minesino.crash.cashout", mult(m), ClientState.money(flightStake * m / 100));
	}

	private long autoHundredths() {
		try {
			return new BigDecimal(autoField.text().replace(',', '.')).multiply(BigDecimal.valueOf(100)).longValue();
		} catch (RuntimeException e) {
			return -1;
		}
	}

	private long liveMultiplier() {
		return CrashGame.multiplierAt(Anim.now() - flightStart);
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {}

	@Override
	public void onShow() {
		super.onShow();
		ClientNet.send("game:crash", "state", new JsonObject(), r -> {
			if (r.ok() && "flying".equals(Json.getString(r.data(), "status", ""))) startFlight(r.data());
		});
	}

	@Override
	protected String stakeProblem() {
		if (autoOn) {
			long t = autoHundredths();
			if (t < Json.getLong(info, "minTarget", 101) || t > Json.getLong(info, "maxTarget", 100000)) return "minesino.error.invalid_multiplier";
		}
		return super.stakeProblem();
	}

	@Override
	protected boolean canPlay() {
		return !flying && super.canPlay();
	}

	@Override
	protected String playLabel() {
		return busy || flying ? "…" : Ui.tr("minesino.crash.launch");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		if (autoOn) p.addProperty("auto", autoHundredths());
		finalCrash = -1;
		cashedAt = 0;
		play("launch", p);
	}

	@Override
	protected void onResult(JsonObject data) {
		if ("flying".equals(Json.getString(data, "status", ""))) {
			startFlight(data);
			Ui.sound(SoundEvents.FIREWORK_ROCKET_LAUNCH, 1f, 0.5f);
		} else {
			endFlight(data);
		}
	}

	private void startFlight(JsonObject d) {
		flying = true;
		boom = false;
		animating = true;
		flightStake = Json.getLong(d, "bet", 0);
		flightStart = Anim.now() - Json.getLong(d, "elapsed", 0);
		held = null; // the stake is already charged; nothing to hide
		lockInputs(true);
	}

	private void cashOut() {
		request("game:crash", "cashout", new JsonObject(), r -> {
			if (r.ok()) endFlight(r.data());
		});
	}

	@Override
	public void onServerEvent(JsonObject data) {
		if (data.has("balance")) ClientState.setBalance(data.get("balance").getAsLong());
		endFlight(data);
	}

	private void endFlight(JsonObject d) {
		flying = false;
		finalCrash = Json.getLong(d, "crash", 100);
		cashedAt = Json.getLong(d, "cashedAt", 0);
		result = d;
		if (d.has("recent")) recent = d.getAsJsonArray("recent");
		if (cashedAt > 0) Ui.sound(SoundEvents.EXPERIENCE_ORB_PICKUP, 1.3f, 0.6f);
		else if (!boom) {
			boom = true;
			Ui.sound(SoundEvents.GENERIC_EXPLODE.value(), 1.2f, 0.3f);
		}
		finishAnimation();
	}

	@Override
	public void tick() {
		// The flight has no fixed length: only the server ends it (cash-out, explosion, auto target).
		if (!flying) super.tick();
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float gx = bx + 26, gy = by + 18, gw = bw - 40, gh = bh - 44;
		for (int i = 0; i <= 4; i++) Ui.rect(g, gx, gy + gh * i / 4f, gw, 0.5f, 0x22FFFFFF);
		long m;
		if (flying) m = liveMultiplier();
		else if (finalCrash > 0) m = cashedAt > 0 ? Math.max(cashedAt, finalCrash) : finalCrash;
		else m = 100;
		double shown = m / 100.0;
		double maxM = Math.max(2.0, shown * 1.25);
		for (int i = 0; i <= 4; i++) {
			double v = 1 + (maxM - 1) * (4 - i) / 4.0;
			Ui.right(g, String.format(java.util.Locale.ROOT, "%.1f×", v), gx - 3, gy + gh * i / 4f - 3, Theme.MUTED);
		}
		if (flying || finalCrash > 0) {
			boolean crashed = !flying;
			int color = crashed && cashedAt == 0 ? Theme.RED : Theme.PINK;
			int steps = 60;
			float lastX = gx, lastY = gy + gh;
			for (int s = 1; s <= steps; s++) {
				double f = s / (double) steps;
				double mv = Math.pow(shown, f);
				float px = gx + gw * (float) f;
				float py = gy + gh - (float) ((mv - 1) / (maxM - 1)) * gh;
				Ui.circle(g, px, py, 1.3f, color);
				Ui.rect(g, px - 0.5f, py, 1.2f, gy + gh - py, Theme.alpha(color, 0.12f));
				lastX = px;
				lastY = py;
			}
			int dot = crashed ? Theme.RED : Theme.GOLD;
			Ui.circleGlow(g, lastX, lastY, 3, 5, Theme.alpha(dot, 0.7f));
			Ui.circle(g, lastX, lastY, crashed ? 4 : 3, dot);
			if (cashedAt > 0 && cashedAt / 100.0 <= maxM) {
				float ty = gy + gh - (float) ((cashedAt / 100.0 - 1) / (maxM - 1)) * gh;
				for (float lx = gx; lx < gx + gw; lx += 4) Ui.rect(g, lx, ty, 2, 0.6f, Theme.alpha(Theme.GREEN, 0.8f));
			}
		}
		double big = flying ? shown : finalCrash > 0 ? (cashedAt > 0 ? cashedAt / 100.0 : finalCrash / 100.0) : 1.0;
		int bigColor = flying ? Theme.TEXT : finalCrash > 0 ? (cashedAt > 0 ? Theme.GREEN : Theme.RED) : Theme.TEXT_DIM;
		float pulse = flying ? 1 + 0.03f * Anim.pulse(0.6f) : 1;
		Ui.scaledCenter(g, String.format(java.util.Locale.ROOT, "%.2f×", big), bx + bw / 2, by + bh / 2 - 14, 2.4f * pulse, bigColor, true);
		if (!flying && finalCrash > 0) {
			Ui.center(g, Ui.tr("minesino.crash.crashed_at", mult(finalCrash)), bx + bw / 2, by + bh / 2 + 10, Theme.RED);
			if (cashedAt > 0) Ui.center(g, Ui.tr("minesino.crash.cashed", mult(cashedAt)), bx + bw / 2, by + bh / 2 + 21, Theme.GREEN);
		} else if (flying) {
			Ui.center(g, Ui.tr("minesino.crash.flying"), bx + bw / 2, by + bh / 2 + 10, Theme.TEXT_DIM);
		} else {
			Ui.center(g, Ui.tr("minesino.crash.hint"), bx + bw / 2, by + bh / 2 + 10, Theme.MUTED);
		}
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		long edge = Json.getLong(info, "edgeBps", 300);
		long t = autoOn ? autoHundredths() : 200;
		if (t > 100) {
			double chance = (10000 - edge) / 100.0 / (t / 100.0);
			Ui.text(g, Ui.fit(Ui.tr("minesino.crash.chance_at", mult(t), String.format(java.util.Locale.ROOT, "%.1f%%", chance)), w), x, y, Theme.MUTED);
		}
	}

	@Override
	protected String recentLabel(JsonObject o) {
		long c = Json.getLong(o, "cashedAt", 0);
		return c > 0 ? "✔ ×" + mult(c) : "@" + mult(Json.getLong(o, "crash", 100)) + "×";
	}

	@Override
	protected String historyChip(JsonObject o) {
		return "×" + mult(Json.getLong(o, "crash", 100));
	}

	/** Coloured by where the rocket blew up, like crash sites: red below ×2, green from ×2, gold from ×10. */
	@Override
	protected int historyColor(JsonObject o) {
		long c = Json.getLong(o, "crash", 100);
		return c >= 1000 ? Theme.GOLD : c >= 200 ? Theme.GREEN : Theme.RED;
	}
}
