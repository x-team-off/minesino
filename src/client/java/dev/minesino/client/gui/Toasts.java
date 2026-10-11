package dev.minesino.client.gui;

import dev.minesino.client.ClientSettings;
import dev.minesino.client.ClientState;
import java.util.Iterator;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Slide-in notifications in the top-right corner (HUD and tablet). */
public final class Toasts {
	private static final long LIFE = 4500;

	private Toasts() {}

	public static void render(GuiGraphicsExtractor g, int screenW, float top) {
		if (!ClientSettings.get().showToasts) return;
		long now = System.currentTimeMillis();
		float y = top;
		Iterator<ClientState.Toast> it = ClientState.toasts.iterator();
		while (it.hasNext()) {
			ClientState.Toast t = it.next();
			long age = now - t.created();
			if (age > LIFE) {
				it.remove();
				continue;
			}
			float in = Anim.easeOutCubic(Math.min(1, age / 250f));
			float out = age > LIFE - 300 ? (LIFE - age) / 300f : 1;
			float w = Math.max(Ui.width(t.title()), Ui.width(t.body())) + 22;
			w = Math.min(w, 220);
			float h = t.body().isEmpty() ? 16 : 26;
			float x = screenW - 6 - w + (1 - in) * (w + 10);
			Ui.glow(g, x, y, w, h, 6, Theme.alpha(t.color(), 0.3f * out));
			Ui.panel(g, x, y, w, h, 6, Theme.alpha(0xF0141019, out), Theme.alpha(t.color(), out));
			Ui.round(g, x + 4, y + 4, 3, h - 8, 1.5f, Theme.alpha(t.color(), out));
			Ui.text(g, Ui.fit(t.title(), w - 16), x + 11, y + 4, Theme.alpha(Theme.TEXT, out));
			if (!t.body().isEmpty()) Ui.text(g, Ui.fit(t.body(), w - 16), x + 11, y + 14, Theme.alpha(t.color(), out));
			y += h + 4;
		}
	}
}
