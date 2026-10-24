package dev.minesino.client.gui.page;

import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.games.GamePages;
import dev.minesino.client.gui.widget.Card;
import dev.minesino.client.gui.widget.Widget;
import java.util.List;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Casino lobby: one card per game the server has enabled. Unavailable games are not shown. */
public class CasinoPage extends Page {
	private float scroll, scrollTarget, contentH;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.casino");
	}

	@Override
	protected void layout() {
		List<String> ids = GamePages.ORDER.stream().filter(screen.data::gameEnabled).toList();
		int cols = w > 400 ? 4 : w > 280 ? 3 : 2;
		float gap = 6;
		float cw = (w - gap * (cols - 1)) / cols;
		float ch = Math.max(68, Math.min(84, (h - 14 - gap * 2) / 3));
		for (int i = 0; i < ids.size(); i++) {
			String id = ids.get(i);
			int c = i % cols, r = i / cols;
			add(new Card(id, Ui.tr("minesino.game." + id), Ui.tr("minesino.game." + id + ".desc"), Ui.tr("minesino.ui.play"),
				() -> screen.navigate(GamePages.create(id)))).bounds(x + c * (cw + gap), y + 14 + r * (ch + gap), cw, ch);
		}
		int rows = (ids.size() + cols - 1) / cols;
		contentH = 14 + rows * (ch + gap);
		scroll = scrollTarget = Math.min(scrollTarget, Math.max(0, contentH - h));
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		scroll += (scrollTarget - scroll) * Math.min(1, dt * 14);
		if (screen.data.casino.getAsJsonObject("games").isEmpty()) {
			Ui.center(g, Ui.tr("minesino.casino.none"), x + w / 2, y + h / 2, Theme.MUTED);
			return;
		}
		Ui.text(g, Ui.tr("minesino.casino.choose"), x, y + 1 - scroll, Theme.TEXT_DIM);
		g.pose().pushMatrix();
		g.pose().translate(0, -scroll);
		boolean inside = my >= y && my < y + h;
		for (Widget wd : widgets) wd.render(g, mx, inside ? (int) (my + scroll) : -1000, dt);
		g.pose().popMatrix();
		if (contentH > h) {
			float bar = h * h / contentH;
			Ui.round(g, x + w + 3, y + (h - bar) * (scroll / (contentH - h)), 2, bar, 1, Theme.alpha(Theme.ACCENT, 0.6f));
		}
		renderError(g);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (my < y || my > y + h) return false;
		return super.mouseClicked(mx, my + scroll, button);
	}

	@Override
	public boolean mouseScrolled(double mx, double my, double amount) {
		scrollTarget = (float) Math.clamp(scrollTarget - amount * 24, 0, Math.max(0, contentH - h));
		return true;
	}
}
