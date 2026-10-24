package dev.minesino.client.hud;

import dev.minesino.client.ClientSettings;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.Anim;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Toasts;
import dev.minesino.client.gui.Ui;
import dev.minesino.tablet.TabletItems;
import net.minecraft.client.AttackIndicatorStatus;
import net.minecraft.client.DeltaTracker;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.player.Player;

/**
 * Draws the tenth hotbar slot with the tablet, its selection frame and the balance right above it.
 * Layout: {@code [1]..[9] [0]}; the slot moves further right when the off-hand slot or the hotbar
 * attack indicator occupies that side, so nothing overlaps.
 */
public final class TabletHud {
	private static float selectAnim;

	private TabletHud() {}

	/** X of the tenth slot's left edge (22 px wide frame, like vanilla). */
	public static int slotX(Minecraft mc, int guiWidth) {
		int x = guiWidth / 2 + 91 + 4;
		Player p = mc.player;
		if (p != null) {
			HumanoidArm offArm = p.getMainArm().getOpposite();
			if (!p.getOffhandItem().isEmpty() && offArm == HumanoidArm.RIGHT) x += 29;
			if (mc.options.attackIndicator().get() == AttackIndicatorStatus.HOTBAR && offArm == HumanoidArm.LEFT) x += 24;
		}
		return x;
	}

	public static void render(GuiGraphicsExtractor g, DeltaTracker delta) {
		Minecraft mc = Minecraft.getInstance();
		if (mc.player == null || mc.player.isSpectator()) return;
		int sw = g.guiWidth(), sh = g.guiHeight();
		int x = slotX(mc, sw);
		int y = sh - 22;
		boolean active = ClientState.tabletActive();
		selectAnim = Anim.approach(selectAnim, active ? 1f : 0f, 1 / 60f, 12f);

		// slot frame in the vanilla hotbar style but with MineSino accents
		Ui.glow(g, x, y, 22, 22, 3 + 3 * selectAnim, Theme.alpha(Theme.ACCENT, 0.25f + 0.45f * selectAnim));
		Ui.panel(g, x, y, 22, 22, 3, 0xD0191011, Theme.lerp(0xFF503135, Theme.GOLD, selectAnim));
		Ui.item(g, TabletItems.displayStack(), x + 3, y + 3, 16);
		if (selectAnim > 0.01f) {
			int c = Theme.alpha(Theme.GOLD, selectAnim);
			float b = 1.2f;
			Ui.round(g, x - 1, y - 1, 24, b, 0.5f, c);
			Ui.round(g, x - 1, y + 23 - b, 24, b, 0.5f, c);
			Ui.round(g, x - 1, y - 1, b, 24, 0.5f, c);
			Ui.round(g, x + 23 - b, y - 1, b, 24, 0.5f, c);
		}
		// key hint "0"
		String key = dev.minesino.client.MineSinoClient.tabletKeyName();
		Ui.scaled(g, key, x + 22 - Ui.width(key) * 0.6f - 1.5f, y + 1.5f, 0.6f, Theme.alpha(Theme.TEXT_DIM, 0.8f), false);

		if (ClientSettings.get().showHudBalance) renderBalance(g, mc, x, y - 3);
		Toasts.render(g, sw, 6);
	}

	/** Balance plate sits right above the slot, starting at its left edge so it never covers the food bar. */
	private static void renderBalance(GuiGraphicsExtractor g, Minecraft mc, float slotLeft, float bottom) {
		String text;
		int color = Theme.GOLD;
		if (!ClientState.balanceLoaded) {
			text = Ui.tr("minesino.hud.loading");
			color = Theme.TEXT_DIM;
		} else {
			long v = ClientState.balance;
			text = ClientSettings.get().compactHudNumbers ? ClientState.moneyCompact(v) : ClientState.money(v);
		}
		float maxW = Math.max(24, g.guiWidth() - slotLeft - 10);
		text = Ui.fit(text, maxW);
		float tw = Ui.width(text);
		float w = Math.max(22, tw + 8), h = 11;
		float x = slotLeft;
		float y = bottom - h;
		Ui.panel(g, x, y, w, h, 4, 0xC8160D0E, Theme.alpha(Theme.GOLD_DARK, 0.9f));
		Ui.textShadow(g, text, x + 4, y + 2, color);
		long since = System.currentTimeMillis() - ClientState.lastBalanceChange;
		if (ClientState.lastDelta != 0 && since < 2000) {
			float t = since / 2000f;
			String d = ClientState.moneySigned(ClientState.lastDelta);
			int dc = ClientState.lastDelta > 0 ? Theme.GREEN : Theme.RED;
			Ui.textShadow(g, d, x + w / 2 - Ui.width(d) / 2f, y - 9 - t * 6, Theme.alpha(dc, 1 - t));
		}
	}
}
