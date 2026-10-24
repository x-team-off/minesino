package dev.minesino.client.gui;

import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.ClientState;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.economy.Json;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** Confirmation for selling a mob (Shift + right click). The server re-validates on confirm. */
public class MobSaleScreen extends Screen {
	private final long offer;
	private final String name;
	private final long price;
	private final long deadline;
	private final long totalMs;
	private final long opened = Anim.now();
	private final Button confirm, cancel;
	private boolean sent;
	private String error;
	private float x, y, w, h;

	public MobSaleScreen(JsonObject data) {
		super(Component.literal("MineSino"));
		offer = Json.getLong(data, "offer", -1);
		name = Json.getString(data, "name", "?");
		price = Json.getLong(data, "price", 0);
		totalMs = Json.getInt(data, "timeout", 20) * 1000L;
		deadline = System.currentTimeMillis() + totalMs;
		confirm = Button.of(Ui.tr("minesino.mob.sell_button"), Button.Style.GOLD, this::doConfirm);
		cancel = Button.of(Ui.tr("minesino.ui.cancel"), Button.Style.GHOST, this::onClose);
		confirm.when(() -> !sent && System.currentTimeMillis() < deadline);
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		w = Math.min(width - 20, 230);
		h = 104;
		x = (width - w) / 2f;
		y = (height - h) / 2f;
		float bw = (w - 30) / 2;
		cancel.bounds(x + 10, y + h - 28, bw, 18);
		confirm.bounds(x + 20 + bw, y + h - 28, bw, 18);
	}

	private void doConfirm() {
		if (sent) return;
		sent = true;
		JsonObject p = new JsonObject();
		p.addProperty("offer", offer);
		ClientNet.send("mob", "confirm", p, reply -> {
			if (reply.ok()) {
				ClientState.toast(Ui.tr("minesino.mob.sold"), name + "  " + ClientState.moneySigned(price), Theme.GREEN);
				Ui.sound(net.minecraft.sounds.SoundEvents.EXPERIENCE_ORB_PICKUP, 1.2f, 0.6f);
				super.onClose();
			} else {
				error = reply.error();
				sent = false;
			}
		});
	}

	@Override
	public void onClose() {
		if (!sent) {
			JsonObject p = new JsonObject();
			p.addProperty("offer", offer);
			ClientNet.send("mob", "cancel", p, null);
		}
		super.onClose();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		Ui.rect(g, 0, 0, width, height, 0x88000000);
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		float t = Anim.easeOutBack(Anim.progress(opened, 250));
		g.pose().pushMatrix();
		float cx = x + w / 2, cy = y + h / 2;
		g.pose().translate(cx, cy);
		g.pose().scale(0.9f + 0.1f * t, 0.9f + 0.1f * t);
		g.pose().translate(-cx, -cy);
		Ui.glow(g, x, y, w, h, 14, Theme.alpha(Theme.GOLD, 0.25f));
		Ui.panel(g, x, y, w, h, 8, Theme.SURFACE, Theme.BORDER_HI);
		Ui.hGradient(g, x + 8, y, w - 16, 1.2f, Theme.ACCENT, Theme.GOLD);
		Ui.logo(g, x + 10, y + 8, 1f);
		Ui.right(g, Ui.tr("minesino.mob.title"), x + w - 10, y + 8, Theme.TEXT_DIM);
		Ui.scaledCenter(g, Ui.fit(name, (w - 20) / 1.3f), x + w / 2, y + 26, 1.3f, Theme.TEXT, false);
		Ui.scaledCenter(g, ClientState.money(price), x + w / 2, y + 42, 1.5f, Theme.GOLD, true);
		long left = Math.max(0, deadline - System.currentTimeMillis());
		float frac = left / (float) Math.max(1, totalMs);
		Ui.round(g, x + 12, y + 61, w - 24, 2, 1, Theme.BG);
		Ui.round(g, x + 12, y + 61, (w - 24) * Math.min(1, frac), 2, 1, left > 5000 ? Theme.ACCENT : Theme.RED);
		if (error != null) Ui.center(g, Ui.fit(Ui.tr(error), w - 16), x + w / 2, y + 66, Theme.RED);
		cancel.render(g, mx, my, 0.016f);
		confirm.render(g, mx, my, 0.016f);
		g.pose().popMatrix();
		if (left == 0 && !sent) error = "minesino.error.offer_expired";
	}

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean dbl) {
		return confirm.mouseClicked(e.x(), e.y(), e.button()) || cancel.mouseClicked(e.x(), e.y(), e.button()) || true;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_RETURN || e.key() == InputConstants.KEY_NUMPADENTER) {
			if (confirm.enabled) doConfirm();
			return true;
		}
		return super.keyPressed(e);
	}
}
