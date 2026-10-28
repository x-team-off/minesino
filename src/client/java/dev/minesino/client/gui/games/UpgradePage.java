package dev.minesino.client.gui.games;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.client.gui.widget.Slider;
import dev.minesino.economy.Json;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.Identifier;
import net.minecraft.sounds.SoundEvents;
import net.minecraft.world.item.ItemStack;

/**
 * Upgrade: stake money or an inventory stack (valued at its sale price) for a chosen multiplier.
 * Chance = (1 - commission) / multiplier, shown before launching.
 */
public class UpgradePage extends GamePage {
	private record Stack(int slot, String item, int count, long value) {}

	private boolean items;
	private Slider slider;
	private long multiplier = 200;
	private final List<Stack> stacks = new ArrayList<>();
	private int selected = -1;
	private float pointer, pointerFrom, pointerTo;
	private boolean success;
	private float listY;

	public UpgradePage() {
		super("upgrade");
	}

	private long minMult() {
		return Json.getLong(info, "minMult", 110);
	}

	private long maxMult() {
		return Json.getLong(info, "maxMult", 10000);
	}

	@Override
	protected float layoutSide(float x, float y, float w) {
		float cw = (w - 4) / 2;
		add(new Button(() -> Ui.tr("minesino.upgrade.money"), Button.Style.GHOST, () -> items = false).select(() -> !items)
			.when(() -> !animating)).bounds(x, y, cw, 15);
		add(new Button(() -> Ui.tr("minesino.upgrade.items"), Button.Style.GHOST, this::switchToItems).select(() -> items)
			.when(() -> !animating && Json.getBool(info, "allowItems", true))).bounds(x + cw + 4, y, cw, 15);
		slider = add(new Slider());
		slider.bounds(x, y + 30, w, 10);
		slider.accent = Theme.GREEN;
		slider.value = toSlider(multiplier);
		slider.onChange = v -> multiplier = fromSlider(v);
		long[] presets = {150, 200, 500, 1000};
		float pw = (w - 12) / 4;
		for (int i = 0; i < presets.length; i++) {
			long q = presets[i];
			add(new Button(() -> "×" + mult(q), Button.Style.CHIP, () -> {
				multiplier = Math.clamp(q, minMult(), maxMult());
				slider.value = toSlider(multiplier);
			}).select(() -> multiplier == q).when(() -> !animating)).bounds(x + i * (pw + 4), y + 44, pw, 13);
		}
		return y + 62;
	}

	private double toSlider(long m) {
		return Math.log((double) m / minMult()) / Math.log((double) maxMult() / minMult());
	}

	private long fromSlider(double v) {
		double m = minMult() * Math.pow((double) maxMult() / minMult(), v);
		long r = Math.round(m);
		if (r > 1000) r = Math.round(r / 100.0) * 100;
		else if (r > 300) r = Math.round(r / 10.0) * 10;
		return Math.clamp(r, minMult(), maxMult());
	}

	private void switchToItems() {
		items = true;
		ClientNet.send("sell", "inventory", new JsonObject(), r -> {
			stacks.clear();
			if (!r.ok()) return;
			for (JsonElement e : r.data().getAsJsonArray("slots")) {
				JsonObject o = e.getAsJsonObject();
				if (!Json.getBool(o, "sellable", false)) continue;
				stacks.add(new Stack(Json.getInt(o, "slot", 0), Json.getString(o, "item", ""), Json.getInt(o, "count", 0), Json.getLong(o, "stack", 0)));
			}
			selected = stacks.isEmpty() ? -1 : 0;
		});
	}

	@Override
	protected void layoutBoard(float x, float y, float w, float h) {
		listY = y + h - 44;
	}

	@Override
	protected boolean usesBetPanel() {
		return true;
	}

	@Override
	protected long currentStake() {
		if (items) return selected >= 0 && selected < stacks.size() ? stacks.get(selected).value() : 0;
		return super.currentStake();
	}

	@Override
	protected String stakeProblem() {
		if (!items) return super.stakeProblem();
		if (selected < 0 || selected >= stacks.size()) return "minesino.upgrade.pick_item";
		long v = stacks.get(selected).value();
		if (v < screen.data.minBet) return "minesino.error.bet_too_small";
		if (v > screen.data.maxBet) return "minesino.error.bet_too_large";
		return null;
	}

	private double chance() {
		long comm = Json.getLong(info, "commissionBps", 500);
		return (10_000 - comm) / 100.0 / (multiplier / 100.0);
	}

	@Override
	protected String playLabel() {
		return busy || animating ? "…" : Ui.tr("minesino.upgrade.go");
	}

	@Override
	protected void onPlay() {
		JsonObject p = new JsonObject();
		p.addProperty("mult", multiplier);
		if (items) {
			Stack s = stacks.get(selected);
			p.addProperty("mode", "items");
			p.addProperty("slot", s.slot());
			p.addProperty("item", s.item());
			p.addProperty("count", s.count());
			p.addProperty("bet", 0);
		} else {
			p.addProperty("mode", "money");
		}
		play("upgrade", p);
		if (items && busy) held = ClientState.balance; // items are staked, not money
	}

	@Override
	protected long animationMs() {
		return 2400;
	}

	@Override
	protected void startAnimation(JsonObject data) {
		success = Json.getBool(data, "success", false);
		double ch = Json.getLong(data, "chancePpm", 500_000) / 1_000_000.0;
		java.util.Random rnd = new java.util.Random();
		pointerFrom = pointer;
		// Cosmetic landing spot consistent with the server outcome.
		pointerTo = (float) (success ? rnd.nextDouble() * ch * 0.96 + ch * 0.02 : ch + (1 - ch) * (0.04 + rnd.nextDouble() * 0.92));
		pointerTo += 3; // three full sweeps before landing
		Ui.sound(SoundEvents.BEACON_ACTIVATE, 1.6f, 0.4f);
	}

	@Override
	protected void finishAnimation() {
		pointer = pointerTo % 1f;
		super.finishAnimation();
		if (items) switchToItems();
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (super.mouseClicked(mx, my, button)) return true;
		if (!items || animating) return false;
		float iw = 22;
		int perRow = (int) ((bw - 16) / iw);
		for (int i = 0; i < stacks.size(); i++) {
			float ix = bx + 8 + (i % perRow) * iw, iy = listY + (i / perRow) * iw;
			if (Ui.inside(mx, my, ix, iy, iw - 2, iw - 2)) {
				selected = i;
				Ui.clickSound();
				return true;
			}
		}
		return false;
	}

	@Override
	protected void renderBoard(GuiGraphicsExtractor g, int mx, int my, float dt) {
		bet.visible = !items;
		double ch = chance() / 100.0;
		float cx = bx + bw / 2;
		float r = Math.min(bw * 0.32f, (bh - 70) / 2f);
		float cy = by + 14 + r;
		float t = animating ? Anim.easeOutCubic(Anim.progress(animStart, animationMs())) : 1;
		float p = animating ? pointerFrom + (pointerTo - pointerFrom) * t : pointer;
		// ring segments: green arc = success chance
		int segs = 90;
		for (int i = 0; i < segs; i++) {
			double a = (i + 0.5) / segs;
			double ang = a * Math.PI * 2;
			boolean win = a < ch;
			float px = cx + (float) Math.sin(ang) * r, py = cy - (float) Math.cos(ang) * r;
			Ui.circle(g, px, py, r * 0.075f, win ? Theme.lerp(Theme.GREEN_DARK, Theme.GREEN, (float) (a / Math.max(0.01, ch))) : 0xFF382326);
		}
		double pa = (p % 1f) * Math.PI * 2;
		float ppx = cx + (float) Math.sin(pa) * r * 0.78f, ppy = cy - (float) Math.cos(pa) * r * 0.78f;
		for (int i = 1; i <= 8; i++) {
			float f = i / 8f;
			Ui.circle(g, cx + (ppx - cx) * f, cy + (ppy - cy) * f, 1.2f, Theme.TEXT);
		}
		Ui.glow(g, ppx - 3, ppy - 3, 6, 6, 4, Theme.alpha(Theme.GOLD, 0.7f));
		Ui.circle(g, ppx, ppy, 3, Theme.GOLD);
		Ui.circle(g, cx, cy, r * 0.38f, Theme.SURFACE);
		Ui.scaledCenter(g, String.format(java.util.Locale.ROOT, "%.2f%%", chance()), cx, cy - 9, 1.3f, Theme.GREEN, true);
		Ui.center(g, "×" + mult(multiplier), cx, cy + 5, Theme.GOLD);
		long stake = currentStake();
		Ui.center(g, ClientState.money(stake) + "  →  " + ClientState.money(stake * multiplier / 100), cx, cy + r + 10, Theme.TEXT);
		if (items) {
			Ui.text(g, Ui.tr("minesino.upgrade.choose_item"), bx + 8, listY - 11, Theme.MUTED);
			float iw = 22;
			int perRow = Math.max(1, (int) ((bw - 16) / iw));
			for (int i = 0; i < stacks.size(); i++) {
				Stack s = stacks.get(i);
				float ix = bx + 8 + (i % perRow) * iw, iy = listY + (i / perRow) * iw;
				if (iy > by + bh) break;
				boolean sel = i == selected;
				Ui.panel(g, ix, iy, iw - 2, iw - 2, 3, sel ? 0xFF1E3A2A : Theme.CARD, sel ? Theme.GREEN : Theme.BORDER);
				ItemStack st = new ItemStack(BuiltInRegistries.ITEM.getValue(Identifier.parse(s.item())), s.count());
				g.item(st, (int) ix + 2, (int) iy + 2);
				g.itemDecorations(Ui.font(), st, (int) ix + 2, (int) iy + 2);
				if (Ui.inside(mx, my, ix, iy, iw - 2, iw - 2)) {
					g.setTooltipForNextFrame(Ui.font(), net.minecraft.network.chat.Component.literal(
						st.getHoverName().getString() + " · " + ClientState.money(s.value())), mx, my);
				}
			}
			if (stacks.isEmpty()) Ui.text(g, Ui.tr("minesino.upgrade.no_items"), bx + 8, listY + 4, Theme.MUTED);
		}
	}

	@Override
	protected void renderSideInfo(GuiGraphicsExtractor g, float x, float y, float w) {
		Ui.text(g, Ui.fit(Ui.tr("minesino.upgrade.formula", mult(Json.getLong(info, "commissionBps", 500))), w), x, y, Theme.MUTED);
	}

	@Override
	protected String recentLabel(JsonObject o) {
		return "×" + mult(Json.getLong(o, "mult", 0)) + (Json.getBool(o, "success", false) ? " ✔" : " ✘");
	}

	@Override
	protected String historyChip(JsonObject o) {
		return "×" + mult(Json.getLong(o, "mult", 0));
	}
}
