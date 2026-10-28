package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.RouletteWheelTexture;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;

/**
 * European roulette (single zero). Click a field to place a chip worth the selected stake,
 * right click to remove it. The wheel spins to the pocket chosen by the server.
 */
public class RoulettePage extends GamePage {
	private static final int[] RED = {1, 3, 5, 7, 9, 12, 14, 16, 18, 19, 21, 23, 25, 27, 30, 32, 34, 36};
	private final Map<String, Long> chips = new LinkedHashMap<>();
	private Map<String, Long> lastChips = new LinkedHashMap<>();
	private float angle, startAngle, targetAngle;
	private int lastNumber = -1;
	private float wheelCx, wheelCy, wheelR;
	private float tx, ty, cellW, cellH;
	private Identifier wheelTex;

	public RoulettePage() {
		super("roulette");
	}

	static boolean red(int n) {
		for (int r : RED) if (r == n) return true;
		return false;
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float bw2 = (w - 4) / 2;
		add(Button.of(Ui.tr("minesino.roulette.clear"), Button.Style.GHOST, chips::clear).when(() -> !animating && !chips.isEmpty()))
			.bounds(x, y, bw2, 15);
		add(Button.of(Ui.tr("minesino.roulette.repeat"), Button.Style.GHOST, () -> {
			chips.clear();
			chips.putAll(lastChips);
		}).when(() -> !animating && !lastChips.isEmpty())).bounds(x + bw2 + 4, y, bw2, 15);
		return y + 20;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		int[] colors = new int[37];
		for (int i = 0; i < 37; i++) {
			int n = RouletteWheelTexture.EUROPEAN_ORDER[i];
			colors[i] = n == 0 ? 0xFF15803D : red(n) ? 0xFFB91C1C : 0xFF141414;
		}
		wheelTex = RouletteWheelTexture.get("european", colors);
		cellW = (w - 16) / 14f;
		cellH = Math.min(15, (h * 0.5f - 10) / 5f);
		float tableH = cellH * 5 + 4;
		tx = x + 8;
		ty = y + h - tableH - 6;
		wheelR = Math.min((ty - y - 10) / 2f, w * 0.22f);
		wheelCx = x + w / 2;
		wheelCy = y + 6 + wheelR;
	}

	private String cellKey(double mx, double my) {
		float rx = (float) (mx - tx), ry = (float) (my - ty);
		if (rx < 0 || ry < 0) return null;
		int col = (int) (rx / cellW), row = (int) (ry / cellH);
		if (row < 3) {
			if (col == 0) return "straight:0";
			if (col >= 1 && col <= 12) {
				int n = (col - 1) * 3 + (3 - row);
				return "straight:" + n;
			}
			if (col == 13) return "column:" + (3 - row);
		} else if (row == 3 && col >= 1 && col <= 12) {
			return "dozen:" + ((col - 1) / 4 + 1);
		} else if (row == 4 && col >= 1 && col <= 12) {
			String[] outs = {"low", "even", "red", "black", "odd", "high"};
			return outs[(col - 1) / 2] + ":0";
		}
		return null;
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (animating || busy) return false;
		String key = cellKey(mx, my);
		if (key == null) return false;
		if (button == Ui.RIGHT) {
			chips.remove(key);
		} else if (button == Ui.LEFT) {
			long v = bet.bet();
			if (v <= 0) return true;
			chips.merge(key, v, Long::sum);
			Ui.sound(SoundEvents.ITEM_PICKUP, 1.6f, 0.4f);
		}
		return true;
	}

	@Override
	protected long currentStake() {
		long t = 0;
		for (long v : chips.values()) t += v;
		return t;
	}

	@Override
	protected String stakeProblem() {
		if (chips.isEmpty()) return "minesino.roulette.place_chips";
		long t = currentStake();
		if (t < screen.data.minBet) return "minesino.error.bet_too_small";
		if (t > screen.data.maxBet) return "minesino.error.bet_too_large";
		if (t > ClientState.balance) return "minesino.error.insufficient_funds";
		return null;
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.roulette.spin");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		JsonArray arr = new JsonArray();
		for (Map.Entry<String, Long> e : chips.entrySet()) {
			String[] k = e.getKey().split(":");
			JsonObject b = new JsonObject();
			b.addProperty("type", k[0]);
			b.addProperty("value", Integer.parseInt(k[1]));
			b.addProperty("amount", e.getValue());
			arr.add(b);
		}
		p.add("bets", arr);
		p.addProperty("bet", currentStake());
		lastChips = new LinkedHashMap<>(chips);
		play("spin", p);
	}

	@Override
	protected long animationMs() {
		return 4200;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		int n = Json.getInt(data, "number", 0);
		int idx = 0;
		for (int i = 0; i < 37; i++) if (RouletteWheelTexture.EUROPEAN_ORDER[i] == n) idx = i;
		float two = (float) (Math.PI * 2);
		startAngle = angle % two;
		float desired = -idx * two / 37f;
		float base = desired - two * 5;
		while (base > startAngle - two * 4) base -= two;
		targetAngle = base;
		Ui.sound(SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, 1.0f, 0.4f);
	}

	@Override
	protected void finishAnimation() {
		angle = targetAngle;
		lastNumber = result == null ? -1 : Json.getInt(result, "number", -1);
		super.finishAnimation();
		chips.clear();
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (animating) {
			float t = Anim.easeOutCubic(Anim.progress(animStart, animationMs()));
			angle = startAngle + (targetAngle - startAngle) * t;
		}
		Ui.circleGlow(g, wheelCx, wheelCy, wheelR, 10, Theme.alpha(Theme.GOLD, 0.35f));
		RouletteWheelTexture.draw(g, wheelTex, wheelCx, wheelCy, wheelR, angle);
		// numbers on the wheel
		for (int i = 0; i < 37; i++) {
			float a = angle + i * (float) (Math.PI * 2 / 37);
			g.pose().pushMatrix();
			g.pose().translate(wheelCx, wheelCy);
			g.pose().rotate(a);
			String s = Integer.toString(RouletteWheelTexture.EUROPEAN_ORDER[i]);
			Ui.scaledCenter(g, s, 0, -wheelR * 0.86f, Math.max(0.35f, wheelR / 110f), 0xFFF5F5F5, false);
			g.pose().popMatrix();
		}
		// pointer
		Ui.round(g, wheelCx - 1.5f, wheelCy - wheelR - 4, 3, 9, 1.5f, Theme.TEXT);
		Ui.circle(g, wheelCx, wheelCy - wheelR - 4, 3, Theme.GOLD);
		// last number badge
		if (lastNumber >= 0 && !animating) {
			int c = lastNumber == 0 ? Theme.GREEN : red(lastNumber) ? Theme.RED : 0xFF2A2A2A;
			Ui.circle(g, wheelCx, wheelCy, wheelR * 0.24f, c);
			Ui.scaledCenter(g, Integer.toString(lastNumber), wheelCx, wheelCy - 5, 1.3f, Theme.TEXT, true);
		}
		renderTable(g, mx, my);
		if (!chips.isEmpty()) {
			Ui.text(g, Ui.tr("minesino.roulette.total", ClientState.money(currentStake())), tx, ty - 11, Theme.GOLD);
		}
	}

	private void renderTable(GuiGraphicsExtractor g, int mx, int my) {
		String hoverKey = cellKey(mx, my);
		List<int[]> winners = new ArrayList<>();
		for (int row = 0; row < 5; row++) {
			for (int col = 0; col < 14; col++) {
				String key = cellKey(tx + col * cellW + 1, ty + row * cellH + 1);
				if (key == null) continue;
				float cx = tx + col * cellW, cy = ty + row * cellH;
				float cw = cellW, ch = cellH;
				if (key.equals("straight:0")) {
					if (row != 0) continue;
					ch = cellH * 3;
				}
				if (row == 3) {
					if ((col - 1) % 4 != 0) continue;
					cw = cellW * 4;
				}
				if (row == 4) {
					if ((col - 1) % 2 != 0) continue;
					cw = cellW * 2;
				}
				int fill = cellColor(key);
				boolean hov = key.equals(hoverKey) && !animating;
				Ui.round(g, cx + 0.5f, cy + 0.5f, cw - 1, ch - 1, 2, hov ? Theme.lerp(fill, 0xFFFFFFFF, 0.2f) : fill);
				String label = cellLabel(key);
				Ui.scaledCenter(g, label, cx + cw / 2, cy + ch / 2 - 3, 0.75f, Theme.TEXT, false);
				Long chip = chips.get(key);
				if (chip != null) {
					Ui.circle(g, cx + cw - 4, cy + 4, 3.5f, Theme.GOLD);
					Ui.circle(g, cx + cw - 4, cy + 4, 2.2f, Theme.GOLD_DARK);
				}
				if (!animating && lastNumber >= 0 && wins(key, lastNumber)) winners.add(new int[] {(int) cx, (int) cy, (int) cw, (int) ch});
			}
		}
		for (int[] r : winners) Ui.glow(g, r[0], r[1], r[2], r[3], 3, Theme.alpha(Theme.GOLD, 0.5f * Anim.pulse(1f)));
		if (hoverKey != null && chips.containsKey(hoverKey)) {
			g.setTooltipForNextFrame(Ui.font(), net.minecraft.network.chat.Component.literal(ClientState.money(chips.get(hoverKey))), mx, my);
		}
	}

	private static boolean wins(String key, int n) {
		String[] k = key.split(":");
		int v = Integer.parseInt(k[1]);
		if (n == 0) return key.equals("straight:0");
		return switch (k[0]) {
			case "straight" -> v == n;
			case "red" -> red(n);
			case "black" -> !red(n);
			case "even" -> n % 2 == 0;
			case "odd" -> n % 2 == 1;
			case "low" -> n <= 18;
			case "high" -> n >= 19;
			case "dozen" -> (n - 1) / 12 + 1 == v;
			case "column" -> (n - 1) % 3 + 1 == v;
			default -> false;
		};
	}

	private static int cellColor(String key) {
		String[] k = key.split(":");
		if (k[0].equals("straight")) {
			int n = Integer.parseInt(k[1]);
			return n == 0 ? 0xFF15803D : red(n) ? 0xFFB91C1C : 0xFF1E1E22;
		}
		if (k[0].equals("red")) return 0xFFB91C1C;
		if (k[0].equals("black")) return 0xFF1E1E22;
		return 0xFF0F5132;
	}

	private static String cellLabel(String key) {
		String[] k = key.split(":");
		return switch (k[0]) {
			case "straight" -> k[1];
			case "column" -> "2:1";
			case "dozen" -> Ui.tr("minesino.roulette.dozen" + k[1]);
			default -> Ui.tr("minesino.roulette." + k[0]);
		};
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		Ui.text(g, Ui.fit(Ui.tr("minesino.roulette.rules"), w), x, y, Theme.MUTED);
	}

	@Override
	protected String recentLabel(JsonObject o) {
		int n = Json.getInt(o, "number", -1);
		return n < 0 ? "?" : "#" + n;
	}

	@Override
	protected String historyChip(JsonObject o) {
		int n = Json.getInt(o, "number", -1);
		return n < 0 ? "?" : Integer.toString(n);
	}

	@Override
	protected int historyColor(JsonObject o) {
		return switch (Json.getString(o, "color", "")) {
			case "red" -> 0xFFE11D2E;
			case "green" -> Theme.GREEN;
			default -> 0xFF9A9AA5;
		};
	}
}
