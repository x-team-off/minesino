package dev.minesino.client.gui.widget;

import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.util.Money;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/**
 * Stake selector shared by every game: editable amount, -/+ steps, ½, ×2, MIN, MAX and quick chips.
 * The last stake is remembered per game. Validity is shown live (limits, balance); the server checks
 * everything again.
 */
public class BetPanel extends Widget {
	private final String gameId;
	private final long min, max;
	private final List<Long> quick;
	private final TextField field = new TextField();
	private final List<Button> buttons = new ArrayList<>();
	public boolean locked;

	public BetPanel(String gameId, long min, long max, List<Long> quick) {
		this.gameId = gameId;
		this.min = min;
		this.max = max;
		this.quick = quick;
		field.allowed = c -> Character.isDigit(c) || c == '.' || c == ',';
		field.maxLength = 14;
		field.suffix = ClientState.symbol;
		long last = ClientState.lastBets.getOrDefault(gameId, quick.isEmpty() ? min : quick.get(Math.min(2, quick.size() - 1)));
		setBet(Math.clamp(last, min, max));
		field.onChange = s -> {
			long v = Money.parse(s);
			if (v > 0) ClientState.lastBets.put(gameId, v);
		};
	}

	public long bet() {
		return Money.parse(field.text());
	}

	public void setBet(long v) {
		v = Math.clamp(v, min, max);
		field.setText(Money.formatNumber(v, v % Money.SCALE == 0 ? 0 : 2).replace(" ", ""));
		ClientState.lastBets.put(gameId, v);
	}

	/** Translation key of the current problem, or null when the stake looks valid. */
	public String problem() {
		long b = bet();
		if (b <= 0) return "minesino.error.invalid_bet";
		if (b < min) return "minesino.error.bet_too_small";
		if (b > max) return "minesino.error.bet_too_large";
		if (ClientState.balanceLoaded && b > ClientState.balance) return "minesino.error.insufficient_funds";
		return null;
	}

	public boolean valid() {
		return problem() == null;
	}

	private long step() {
		long b = Math.max(bet(), min);
		long s = 1;
		while (s * 10 <= b / 5) s *= 10;
		return Math.max(Money.SCALE, s);
	}

	@Override
	public Widget bounds(float x, float y, float w, float h) {
		super.bounds(x, y, w, h);
		buttons.clear();
		// Row 1: [-] [ amount ] [+]   Row 2: half, double, MIN, MAX   Row 3: quick chips
		float gap = 3;
		float rowH = Math.min(15, (h - 2 * gap) / 3);
		float bw = 16;
		buttons.add(bnd(Button.of("−", Button.Style.GHOST, () -> setBet(Math.max(min, bet() - step()))), x, y, bw, rowH));
		field.bounds(x + bw + gap, y, w - 2 * (bw + gap), rowH);
		buttons.add(bnd(Button.of("+", Button.Style.GHOST, () -> setBet(bet() + step())), x + w - bw, y, bw, rowH));
		String[] labels = {"½", "×2", "MIN", "MAX"};
		Runnable[] acts = {() -> setBet(Math.max(min, bet() / 2)), () -> setBet(bet() * 2), () -> setBet(min),
			() -> setBet(Math.min(max, ClientState.balanceLoaded ? Math.max(min, ClientState.balance) : max))};
		float smallW = (w - gap * 3) / 4;
		for (int i = 0; i < 4; i++) {
			buttons.add(bnd(Button.of(labels[i], Button.Style.GHOST, acts[i]), x + i * (smallW + gap), y + rowH + gap, smallW, rowH));
		}
		if (!quick.isEmpty()) {
			float chipW = (w - gap * (quick.size() - 1)) / quick.size();
			for (int i = 0; i < quick.size(); i++) {
				long q = quick.get(i);
				Button chip = new Button(() -> chipLabel(q), Button.Style.CHIP, () -> setBet(q));
				chip.select(() -> bet() == q);
				buttons.add(bnd(chip, x + i * (chipW + gap), y + 2 * (rowH + gap), chipW, rowH));
			}
		}
		return this;
	}

	/** 1000 MS -> "1K", 2500 MS -> "2.5K", 50 MS -> "50". */
	static String chipLabel(long units) {
		long whole = units / Money.SCALE;
		if (whole >= 1_000_000) return trim(whole / 1_000_000.0) + "M";
		if (whole >= 1000) return trim(whole / 1000.0) + "K";
		return Money.formatNumber(units, units % Money.SCALE == 0 ? 0 : 2);
	}

	private static String trim(double v) {
		String s = String.format(java.util.Locale.ROOT, "%.1f", Math.floor(v * 10) / 10);
		return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
	}

	private static Button bnd(Button b, float x, float y, float w, float h) {
		b.bounds(x, y, w, h);
		return b;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (!visible) return;
		for (Button b : buttons) {
			b.enabled = !locked;
			b.active = () -> !locked;
			b.render(g, mx, my, dt);
		}
		field.render(g, mx, my, dt);
		if (!valid() && !field.text().isEmpty()) {
			Ui.round(g, field.x + field.w - 3, field.y + 2, 2, 2, 1, Theme.RED);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (locked || !visible) return false;
		if (field.mouseClicked(mx, my, button)) return true;
		for (Button b : buttons) if (b.mouseClicked(mx, my, button)) return true;
		return false;
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		if (locked || !field.isOver(mx, my)) return false;
		setBet(amount > 0 ? bet() + step() : Math.max(min, bet() - step()));
		return true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		return !locked && field.keyPressed(e);
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return !locked && field.charTyped(e);
	}

	@Override
	public boolean isFocused() {
		return field.isFocused();
	}

	@Override
	public void setFocused(boolean f) {
		field.setFocused(f);
	}
}
