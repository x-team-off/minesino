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
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** Mines 5x5: reveal safe tiles to raise the multiplier, cash out any time. */
public class MinesPage extends GamePage {
	private static final ItemStack GEM = new ItemStack(Items.EMERALD);
	private static final ItemStack BOMB = new ItemStack(Items.TNT);
	private int mines = 3;
	private boolean active;
	private final boolean[] open = new boolean[25];
	private final boolean[] mine = new boolean[25];
	private final long[] revealedAt = new long[25];
	private long mult = 100, nextMult;
	private long cashout;
	private float gx, gy, tile;
	private boolean finished;
	private float countX, countY, countW;

	public MinesPage() {
		super("mines");
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		int[] presets = {1, 3, 5, 10, 24};
		float cw = (w - 16) / 5;
		for (int i = 0; i < presets.length; i++) {
			int n = presets[i];
			add(new Button(() -> Integer.toString(n), Button.Style.CHIP, () -> mines = n).select(() -> mines == n).when(() -> !active))
				.bounds(x + i * (cw + 4), y, cw, 14);
		}
		countX = x;
		countY = y + 18;
		countW = w;
		add(Button.of("−", Button.Style.GHOST, () -> mines = Math.max(1, mines - 1)).when(() -> !active)).bounds(x, y + 18, 18, 14);
		add(Button.of("+", Button.Style.GHOST, () -> mines = Math.min(24, mines + 1)).when(() -> !active)).bounds(x + w - 18, y + 18, 18, 14);
		Button cash = add(new Button(() -> Ui.tr("minesino.mines.cashout", ClientState.money(cashout)), Button.Style.SUCCESS, this::doCashout));
		cash.when(() -> active && !busy && cashout > 0).bounds(x, y + 36, w, 18);
		return y + 58;
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		tile = Math.min((w - 30) / 5f, (h - 30) / 5f);
		gx = x + (w - tile * 5) / 2;
		gy = y + (h - tile * 5) / 2 + 6;
	}

	@Override
	public void onShow() {
		super.onShow();
		ClientNet.send("game:mines", "state", new JsonObject(), r -> {
			if (r.ok() && "active".equals(Json.getString(r.data(), "status", ""))) apply(r.data());
		});
	}

	@Override
	protected boolean canPlay() {
		return !active && super.canPlay();
	}

	@Override
	protected String playLabel() {
		return busy ? "…" : Ui.tr("minesino.mines.start");
	}

	@Override
	protected void onPlay() {
		java.util.Arrays.fill(open, false);
		java.util.Arrays.fill(mine, false);
		java.util.Arrays.fill(revealedAt, 0);
		finished = false;
		JsonObject p = new JsonObject();
		p.addProperty("mines", mines);
		play("start", p);
	}

	@Override
	protected void onResult(JsonObject data) {
		apply(data);
	}

	private void apply(JsonObject d) {
		active = "active".equals(Json.getString(d, "status", ""));
		mines = Json.getInt(d, "mines", mines);
		long now = Anim.now();
		for (JsonElement e : d.getAsJsonArray("open")) {
			int i = e.getAsInt();
			if (!open[i]) revealedAt[i] = now;
			open[i] = true;
		}
		if (d.has("minePositions")) for (JsonElement e : d.getAsJsonArray("minePositions")) mine[e.getAsInt()] = true;
		mult = Json.getLong(d, "mult", 100);
		nextMult = Json.getLong(d, "nextMult", 0);
		int revealed = Json.getInt(d, "revealed", 0);
		long stake = Json.getLong(d, "bet", 0);
		cashout = revealed == 0 ? 0 : stake * mult / 100;
		lockInputs(active);
		if (active) {
			held = null;
		} else {
			finished = true;
			result = d;
			if (d.has("recent")) recent = d.getAsJsonArray("recent");
			animating = true;
			animStart = now;
			if ("boom".equals(Json.getString(d, "result", ""))) Ui.sound(SoundEvents.GENERIC_EXPLODE.value(), 1.0f, 0.4f);
		}
	}

	@Override
	protected long animationMs() {
		return 500;
	}

	private void reveal(int i) {
		JsonObject p = new JsonObject();
		p.addProperty("tile", i);
		request("game:mines", "reveal", p, r -> {
			if (!r.ok()) return;
			apply(r.data());
			if (active) Ui.sound(SoundEvents.AMETHYST_BLOCK_CHIME, 1.0f + Json.getInt(r.data(), "revealed", 0) * 0.05f, 0.8f);
		});
	}

	private void doCashout() {
		request("game:mines", "cashout", new JsonObject(), r -> {
			if (r.ok()) apply(r.data());
		});
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (!active || busy || button != Ui.LEFT) return false;
		int c = (int) ((mx - gx) / tile), r = (int) ((my - gy) / tile);
		if (mx < gx || my < gy || c > 4 || r > 4) return false;
		int i = r * 5 + c;
		if (!open[i]) reveal(i);
		return true;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		Ui.center(g, active ? Ui.tr("minesino.mines.current", mult(mult), mult(nextMult))
			: Ui.tr("minesino.mines.info", mines), bx + bw / 2, by + 8, active ? Theme.GOLD : Theme.TEXT_DIM);
		for (int i = 0; i < 25; i++) {
			int c = i % 5, r = i / 5;
			float x0 = gx + c * tile + 2, y0 = gy + r * tile + 2, s = tile - 4;
			boolean hov = active && !open[i] && Ui.inside(mx, my, x0, y0, s, s);
			if (open[i] || (finished && mine[i])) {
				float t = revealedAt[i] == 0 ? 1 : Anim.easeOutBack(Anim.progress(revealedAt[i], 250));
				boolean isMine = mine[i];
				int fill = isMine ? (open[i] ? 0xFF7F1D1D : 0xFF3A1720) : 0xFF0E3B26;
				Ui.panel(g, x0, y0, s, s, 4, fill, isMine ? Theme.RED : Theme.GREEN);
				float is = s * 0.6f * t;
				Ui.item(g, isMine ? BOMB : GEM, x0 + (s - is) / 2, y0 + (s - is) / 2, Math.max(1, is));
			} else {
				int fill = hov ? Theme.CARD_HI : Theme.CARD;
				if (hov) Ui.glow(g, x0, y0, s, s, 4, Theme.alpha(Theme.PURPLE, 0.4f));
				Ui.panel(g, x0, y0 - (hov ? 1 : 0), s, s, 4, fill, hov ? Theme.PURPLE : Theme.BORDER);
				Ui.gradient(g, x0 + 1, y0 + 1, s - 2, s * 0.4f, 0x18FFFFFF, 0x00000000);
				if (finished) Ui.rect(g, x0, y0, s, s, 0x55000000);
			}
		}
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		Ui.center(g, Ui.tr("minesino.mines.count", mines), countX + countW / 2, countY + 3.5f, Theme.TEXT);
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return Ui.tr("minesino.mines.recent", Json.getInt(o, "mines", 0), Json.getInt(o, "revealed", 0));
	}
}
