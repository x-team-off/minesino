package dev.minesino.client.gui;

import dev.minesino.client.ClientState;
import dev.minesino.client.gui.page.HomePage;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.network.MineSinoNetwork;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/**
 * The tablet UI: a single custom-drawn screen hosting pages with animated transitions, a header
 * with logo and live balance, modal confirmations and toasts. It never pauses the game, so the
 * integrated server keeps processing requests in single player.
 */
public class MineSinoScreen extends Screen {
	public final TabletData data;
	private Page page;
	private Page outgoing;
	private long transitionStart;
	private int transitionDir = 1;
	private final Deque<Page> history = new ArrayDeque<>();
	private Modal modal;
	private float px, py, pw, ph;
	private final Button back = Button.of("←", Button.Style.GHOST, this::back);
	private final Button close = Button.of("✕", Button.Style.GHOST, this::onClose);
	private long lastFrame = Anim.now();
	private final long openedAt = Anim.now();
	private double shownBalance = ClientState.balance;

	public MineSinoScreen(TabletData data) {
		this(data, null);
	}

	public MineSinoScreen(TabletData data, Page start) {
		super(Component.literal("MineSino"));
		this.data = data;
		this.page = start != null ? start : new HomePage();
	}

	@Override
	public boolean isPauseScreen() {
		return false;
	}

	@Override
	protected void init() {
		pw = Math.min(width - 12, 500);
		ph = Math.min(height - 12, 310);
		px = (width - pw) / 2f;
		py = (height - ph) / 2f;
		back.bounds(px + pw - 44, py + 8, 16, 16);
		close.bounds(px + pw - 24, py + 8, 16, 16);
		float[] c = content();
		page.init(this, c[0], c[1], c[2], c[3]);
		if (outgoing != null) outgoing.init(this, c[0], c[1], c[2], c[3]);
	}

	/** Content rectangle below the header: x, y, w, h. */
	public float[] content() {
		return new float[] {px + 10, py + 32, pw - 20, ph - 40};
	}

	public void navigate(Page next) {
		history.push(page);
		switchTo(next, 1);
	}

	public void back() {
		if (modal != null) {
			modal.cancel();
			modal = null;
			return;
		}
		if (history.isEmpty()) {
			onClose();
			return;
		}
		switchTo(history.pop(), -1);
	}

	public void home() {
		history.clear();
		switchTo(new HomePage(), -1);
	}

	private void switchTo(Page next, int dir) {
		page.onHide();
		outgoing = page;
		page = next;
		transitionDir = dir;
		transitionStart = Anim.now();
		float[] c = content();
		page.init(this, c[0], c[1], c[2], c[3]);
		page.onShow();
	}

	public void openModal(Modal m) {
		modal = m;
	}

	public void closeModal() {
		modal = null;
	}

	public Page page() {
		return page;
	}

	@Override
	public void added() {
		page.onShow();
	}

	@Override
	public void removed() {
		page.onHide();
		if (minecraft != null) minecraft.textInputManager().stopTextInput();
		if (minecraft != null) minecraft.textInputManager().stopTextInput();
		if (minecraft != null) minecraft.textInputManager().stopTextInput();
		// Unfinished multi-step rounds are settled by the server when the tablet closes.
		if (ClientPlayNetworking.canSend(MineSinoNetwork.TabletClosed.TYPE)) {
			ClientPlayNetworking.send(new MineSinoNetwork.TabletClosed());
		}
	}

	@Override
	public void tick() {
		page.tick();
	}

	@Override
	public void extractBackground(GuiGraphicsExtractor g, int mx, int my, float a) {
		float t = Anim.easeOutCubic(Anim.progress(openedAt, 250));
		Ui.gradient(g, 0, 0, width, height, Theme.alpha(0xD0050409, t), Theme.alpha(0xE0120A1E, t));
	}

	@Override
	public void extractRenderState(GuiGraphicsExtractor g, int mx, int my, float a) {
		long now = Anim.now();
		float dt = Math.min(0.1f, (now - lastFrame) / 1000f);
		lastFrame = now;
		float open = Anim.easeOutBack(Anim.progress(openedAt, 320));
		g.pose().pushMatrix();
		float cx = px + pw / 2, cy = py + ph / 2;
		float k = 0.94f + 0.06f * open;
		g.pose().translate(cx, cy + (1 - open) * 8);
		g.pose().scale(k, k);
		g.pose().translate(-cx, -cy);
		renderFrame(g, mx, my, dt);
		renderPages(g, mx, my, dt);
		g.pose().popMatrix();
		if (modal != null) {
			if (modal.closed) modal = null;
			else modal.render(g, mx, my, dt, width, height);
		}
		renderToasts(g);
	}

	private void renderFrame(GuiGraphicsExtractor g, int mx, int my, float dt) {
		// outer glow + panel
		Ui.glow(g, px, py, pw, ph, 18, Theme.alpha(Theme.PURPLE, 0.18f + 0.08f * Anim.pulse(4f)));
		Ui.glow(g, px, py + 4, pw, ph, 10, 0x88000000);
		Ui.panel(g, px, py, pw, ph, 10, Theme.BG, Theme.BORDER_HI);
		Ui.gradient(g, px + 1, py + 1, pw - 2, 28, 0xFF1A1526, Theme.BG);
		Ui.hGradient(g, px + 10, py + 29, pw - 20, 0.8f, Theme.alpha(Theme.PURPLE, 0.8f), Theme.alpha(Theme.GOLD, 0.8f));
		Ui.logo(g, px + 12, py + 9, 1.4f);
		String title = page.title();
		float logoW = Ui.width("MineSino") * 1.4f;
		if (title != null && !title.isEmpty()) {
			Ui.text(g, "·", px + 18 + logoW, py + 12, Theme.MUTED);
			Ui.text(g, Ui.fit(title, pw / 2.6f), px + 26 + logoW, py + 12, Theme.TEXT_DIM);
		}
		// balance pill with count-up animation
		Long held = page.heldBalance();
		long target = held != null ? held : ClientState.balance;
		if (!ClientState.balanceLoaded || !dev.minesino.client.ClientSettings.get().animations) shownBalance = target;
		else shownBalance += (target - shownBalance) * Math.min(1, dt * 10);
		if (Math.abs(target - shownBalance) < 1) shownBalance = target;
		String bal = ClientState.balanceLoaded ? ClientState.money(Math.round(shownBalance)) : Ui.tr("minesino.hud.loading");
		float bw = Ui.width(bal) + 22;
		float bx = px + pw - 52 - bw;
		Ui.glow(g, bx, py + 8, bw, 16, 5, Theme.alpha(Theme.GOLD, 0.25f));
		Ui.panel(g, bx, py + 8, bw, 16, 8, 0xFF221A0C, Theme.GOLD_DARK);
		Ui.circle(g, bx + 9, py + 16, 4, Theme.GOLD);
		Ui.center(g, "$", bx + 9.5f, py + 12.5f, 0xFF3A2606);
		Ui.text(g, bal, bx + 16, py + 12.5f, Theme.GOLD);
		back.visible = !history.isEmpty();
		back.render(g, mx, my, dt);
		close.render(g, mx, my, dt);
	}

	private void renderPages(GuiGraphicsExtractor g, int mx, int my, float dt) {
		float[] c = content();
		float t = Anim.easeOutCubic(Anim.progress(transitionStart, 260));
		g.enableScissor((int) c[0] - 2, (int) c[1] - 2, (int) (c[0] + c[2]) + 2, (int) (c[1] + c[3]) + 2);
		if (outgoing != null && t < 1) {
			g.pose().pushMatrix();
			g.pose().translate(-transitionDir * 30 * t, 0);
			outgoing.render(g, -1000, -1000, dt);
			g.pose().popMatrix();
			Ui.rect(g, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4, Theme.alpha(Theme.BG, t));
		} else {
			outgoing = null;
		}
		g.pose().pushMatrix();
		g.pose().translate(transitionDir * 30 * (1 - t), 0);
		boolean interactive = modal == null && t >= 0.6f;
		page.render(g, interactive ? mx : -1000, interactive ? my : -1000, dt);
		g.pose().popMatrix();
		if (t < 1) Ui.rect(g, c[0] - 2, c[1] - 2, c[2] + 4, c[3] + 4, Theme.alpha(Theme.BG, 1 - t));
		g.disableScissor();
	}

	private void renderToasts(GuiGraphicsExtractor g) {
		Toasts.render(g, width, 6);
	}

	// ------------------------------------------------------------------ input

	@Override
	public boolean mouseClicked(MouseButtonEvent e, boolean doubleClick) {
		double mx = e.x(), my = e.y();
		if (modal != null) return modal.mouseClicked(mx, my, e.button());
		if (back.mouseClicked(mx, my, e.button()) || close.mouseClicked(mx, my, e.button())) return true;
		return page.mouseClicked(mx, my, e.button());
	}

	@Override
	public boolean mouseReleased(MouseButtonEvent e) {
		return modal == null && page.mouseReleased(e.x(), e.y(), e.button());
	}

	@Override
	public boolean mouseDragged(MouseButtonEvent e, double dx, double dy) {
		return modal == null && page.mouseDragged(e.x(), e.y(), e.button());
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double sx, double sy) {
		return modal == null && page.mouseScrolled(mx, my, sy);
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (e.key() == InputConstants.KEY_ESCAPE) {
			if (modal != null || page.hasFocusedInput()) {
				if (modal != null) back();
				else page.keyPressed(e);
				return true;
			}
			if (!history.isEmpty()) {
				back();
				return true;
			}
			onClose();
			return true;
		}
		if (modal == null && page.keyPressed(e)) return true;
		if (minecraft != null && minecraft.options.keyInventory.matches(e) && !page.hasFocusedInput()) {
			onClose();
			return true;
		}
		return true; // keep game keybinds inactive while the tablet is open
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		return modal == null && page.charTyped(e);
	}

	public static List<String> lines(String... s) {
		return List.of(s);
	}
}
