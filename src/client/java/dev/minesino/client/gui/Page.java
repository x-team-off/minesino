package dev.minesino.client.gui;

import com.google.gson.JsonObject;
import dev.minesino.client.ClientNet;
import dev.minesino.client.gui.widget.Widget;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** One page of the tablet (home, a game, the shop...). Lays out widgets inside the content area. */
public abstract class Page {
	protected MineSinoScreen screen;
	protected float x, y, w, h;
	protected final List<Widget> widgets = new ArrayList<>();
	/** True while a request is in flight: inputs that send requests are disabled (no double submits). */
	protected boolean busy;
	private long busySince;
	protected String errorKey;
	protected long errorAt;

	public abstract String title();

	public void init(MineSinoScreen screen, float x, float y, float w, float h) {
		this.screen = screen;
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		widgets.clear();
		layout();
	}

	protected abstract void layout();

	/** Called once when the page becomes visible (not on resize). */
	public void onShow() {}

	public void onHide() {}

	protected <T extends Widget> T add(T widget) {
		widgets.add(widget);
		return widget;
	}

	public void tick() {
		if (busy && Anim.now() - busySince > 8000) {
			busy = false;
			showError("minesino.error.timeout");
		}
	}

	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		renderContent(g, mx, my, dt);
		for (Widget wd : widgets) wd.render(g, mx, my, dt);
		renderOverlay(g, mx, my, dt);
		renderError(g);
	}

	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {}

	protected void renderOverlay(GuiGraphicsExtractor g, int mx, int my, float dt) {}

	protected void renderError(GuiGraphicsExtractor g) {
		if (errorKey == null) return;
		float t = (Anim.now() - errorAt) / 3500f;
		if (t > 1) {
			errorKey = null;
			return;
		}
		float a = t < 0.85f ? 1 : (1 - t) / 0.15f;
		String msg = Ui.tr(errorKey);
		float tw = Ui.width(msg) + 16;
		float ex = x + (w - tw) / 2, ey = y + h - 18 + (1 - Anim.easeOutCubic(Math.min(1, t * 8))) * 6;
		Ui.glow(g, ex, ey, tw, 14, 6, Theme.alpha(Theme.RED, 0.35f * a));
		Ui.panel(g, ex, ey, tw, 14, 7, Theme.alpha(0xFF2A1015, a), Theme.alpha(Theme.RED, a));
		Ui.center(g, msg, ex + tw / 2, ey + 3.5f, Theme.alpha(0xFFFFD5D5, a));
	}

	public void showError(String key) {
		errorKey = key == null ? "minesino.error.internal" : key;
		errorAt = Anim.now();
		Ui.sound(net.minecraft.sounds.SoundEvents.NOTE_BLOCK_BASS.value(), 0.7f, 0.4f);
	}

	/** Sends a request; the callback runs only if this page is still the active one. */
	protected void request(String channel, String action, JsonObject params, Consumer<ClientNet.Reply> onReply) {
		if (busy) return;
		busy = true;
		busySince = Anim.now();
		ClientNet.send(channel, action, params, reply -> {
			busy = false;
			if (screen == null) return;
			if (!reply.ok()) showError(reply.error());
			onReply.accept(reply);
		});
	}

	// ------------------------------------------------------------------ input

	public boolean mouseClicked(double mx, double my, int button) {
		boolean handled = false;
		for (int i = widgets.size() - 1; i >= 0 && !handled; i--) {
			Widget wd = widgets.get(i);
			if (wd.visible && wd.mouseClicked(mx, my, button)) handled = true;
		}
		for (Widget wd : widgets) {
			if (wd.isFocused() && !wd.isOver(mx, my)) wd.setFocused(false);
		}
		return handled;
	}

	public boolean mouseReleased(double mx, double my, int button) {
		boolean r = false;
		for (Widget wd : widgets) r |= wd.mouseReleased(mx, my, button);
		return r;
	}

	public boolean mouseDragged(double mx, double my, int button) {
		for (Widget wd : widgets) if (wd.mouseDragged(mx, my, button)) return true;
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		for (Widget wd : widgets) if (wd.visible && wd.mouseScrolled(mx, my, amount)) return true;
		return false;
	}

	public boolean keyPressed(KeyEvent e) {
		for (Widget wd : widgets) if (wd.isFocused() && wd.keyPressed(e)) return true;
		return false;
	}

	public boolean charTyped(CharacterEvent e) {
		for (Widget wd : widgets) if (wd.isFocused() && wd.charTyped(e)) return true;
		return false;
	}

	public boolean hasFocusedInput() {
		for (Widget wd : widgets) if (wd.isFocused()) return true;
		return false;
	}

	/** Pages showing an unfinished animation keep the old balance in the header until it ends. */
	public Long heldBalance() {
		return null;
	}
}
