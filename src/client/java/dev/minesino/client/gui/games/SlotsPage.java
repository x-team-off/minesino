package dev.minesino.client.gui.games;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;

/** 3x3 slot machine with 5 paylines. The server sends the final grid; reels animate into it. */
public class SlotsPage extends GamePage {
	private final List<String> symbols = new ArrayList<>();
	private final Map<String, Long> pay3 = new HashMap<>();
	private final Map<String, ItemStack> icons = new HashMap<>();
	private String[][] grid;
	private final List<Integer> winLines = new ArrayList<>();
	private float rx, ry, cell;
	private final boolean[] stopped = new boolean[3];

	public SlotsPage() {
		super("slots");
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		symbols.clear();
		if (info.has("symbols")) {
			for (JsonElement e : info.getAsJsonArray("symbols")) {
				JsonObject o = e.getAsJsonObject();
				String id = Json.getString(o, "id", "apple");
				symbols.add(id);
				pay3.put(id, Json.getLong(o, "pay3", 0));
			}
		}
		if (symbols.isEmpty()) symbols.add("apple");
		if (grid == null) {
			grid = new String[3][3];
			for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) grid[r][c] = symbols.get((r * 3 + c * 2) % symbols.size());
		}
		cell = Math.min((w - 40) / 3f, (h - 70) / 3f);
		rx = x + (w - cell * 3) / 2;
		ry = y + 14;
	}

	private ItemStack icon(String id) {
		return icons.computeIfAbsent(id, k -> {
			var item = BuiltInRegistries.ITEM.getValue(Identifier.withDefaultNamespace(k));
			return new ItemStack(item == null ? Items.BARRIER : item);
		});
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.slots.spin");
	}

	@Override
	protected void onPlay() {
		winLines.clear();
		play("spin", new JsonObject());
	}

	@Override
	protected long animationMs() {
		return 2300;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		JsonArray gr = data.getAsJsonArray("grid");
		for (int r = 0; r < 3; r++) for (int c = 0; c < 3; c++) grid[r][c] = gr.get(r).getAsJsonArray().get(c).getAsString();
		winLines.clear();
		for (JsonElement e : data.getAsJsonArray("lines")) winLines.add(e.getAsJsonObject().get("line").getAsInt());
		for (int i = 0; i < 3; i++) stopped[i] = false;
	}

	private long reelStop(int reel) {
		return 1000 + reel * 450L;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float mw = cell * 3 + 16, mh = cell * 3 + 16;
		float mx0 = rx - 8, my0 = ry - 8;
		Ui.glow(g, mx0, my0, mw, mh, 10, Theme.alpha(Theme.GOLD, 0.2f + 0.1f * Anim.pulse(2f)));
		Ui.panel(g, mx0, my0, mw, mh, 9, 0xFF2A1F0C, Theme.GOLD);
		long elapsed = (long) ((Anim.now() - animStart) * dev.minesino.client.ClientSettings.get().animationSpeed);
		for (int reel = 0; reel < 3; reel++) {
			float x0 = rx + reel * cell;
			Ui.round(g, x0 + 2, ry, cell - 4, cell * 3, 5, 0xFFF7F3EA);
			Ui.gradient(g, x0 + 2, ry, cell - 4, cell * 0.5f, 0x55000000, 0x00000000);
			Ui.gradient(g, x0 + 2, ry + cell * 2.5f, cell - 4, cell * 0.5f, 0x00000000, 0x55000000);
			boolean spinning = animating && elapsed < reelStop(reel) && dev.minesino.client.ClientSettings.get().animations;
			if (animating && !spinning && !stopped[reel]) {
				stopped[reel] = true;
				Ui.sound(SoundEvents.NOTE_BLOCK_HAT.value(), 1.2f, 0.5f);
			}
			g.enableScissor((int) (x0 + 2), (int) ry, (int) (x0 + cell - 2), (int) (ry + cell * 3));
			if (spinning) {
				float speed = cell * 0.035f;
				float off = (elapsed * speed) % cell;
				int baseIdx = (int) (elapsed * speed / cell);
				for (int k = -1; k < 4; k++) {
					String sym = symbols.get(Math.floorMod(baseIdx * 7 + reel * 3 - k * 5, symbols.size()));
					float sy = ry + k * cell + off - cell;
					Ui.item(g, icon(sym), x0 + cell * 0.2f, sy + cell * 0.2f, cell * 0.6f);
				}
			} else {
				float bounce = 0;
				if (animating) {
					float t = Math.min(1, (elapsed - reelStop(reel)) / 250f);
					bounce = (1 - Anim.easeOutBack(t)) * cell * 0.3f;
				}
				for (int row = 0; row < 3; row++) {
					Ui.item(g, icon(grid[row][reel]), x0 + cell * 0.2f, ry + row * cell + cell * 0.2f - bounce, cell * 0.6f);
				}
			}
			g.disableScissor();
		}
		// paylines
		if (!animating && !winLines.isEmpty()) {
			float pulse = 0.5f + 0.5f * Anim.pulse(0.8f);
			for (int line : winLines) {
				int[] rows = dev.minesino.casino.games.SlotsGame.LINE_ROWS[line];
				for (int reel = 0; reel < 3; reel++) {
					float cx = rx + reel * cell + cell / 2, cy = ry + rows[reel] * cell + cell / 2;
					Ui.glow(g, cx - cell * 0.35f, cy - cell * 0.35f, cell * 0.7f, cell * 0.7f, 4, Theme.alpha(lineColor(line), 0.45f * pulse));
					if (reel < 2) {
						float nx = rx + (reel + 1) * cell + cell / 2, ny = ry + rows[reel + 1] * cell + cell / 2;
						for (int s = 0; s <= 10; s++) {
							float t = s / 10f;
							Ui.circle(g, cx + (nx - cx) * t, cy + (ny - cy) * t, 1.1f, Theme.alpha(lineColor(line), 0.9f));
						}
					}
				}
			}
		}
		// paytable
		float py = ry + cell * 3 + 14;
		float colW = bw / Math.min(4, Math.max(1, symbols.size()));
		int i = 0;
		for (String s : symbols) {
			float px2 = bx + 6 + (i % 4) * colW;
			float py2 = py + (i / 4) * 13;
			if (py2 + 12 > by + bh) break;
			Ui.item(g, icon(s), px2, py2, 10);
			Ui.text(g, "×" + mult(pay3.getOrDefault(s, 0L)), px2 + 13, py2 + 1, Theme.GOLD);
			i++;
		}
	}

	private static int lineColor(int line) {
		return switch (line) {
			case 0 -> Theme.GOLD;
			case 1 -> Theme.NEON;
			case 2 -> Theme.PINK;
			case 3 -> Theme.GREEN;
			default -> Theme.PURPLE;
		};
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		String pair = Json.getString(info, "pairSymbol", "");
		if (!pair.isEmpty()) {
			Ui.item(g, icon(pair), x, y - 1, 9);
			Ui.item(g, icon(pair), x + 9, y - 1, 9);
			Ui.text(g, "= ×" + mult(Json.getLong(info, "pairMult", 0)) + "  · " + Ui.tr("minesino.slots.lines"), x + 21, y, Theme.MUTED);
		}
	}
}
