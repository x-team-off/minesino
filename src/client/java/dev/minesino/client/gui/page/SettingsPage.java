package dev.minesino.client.gui.page;

import dev.minesino.client.ClientSettings;
import dev.minesino.client.MineSinoClient;
import dev.minesino.client.gui.Page;
import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import dev.minesino.client.gui.widget.Button;
import dev.minesino.client.gui.widget.Slider;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import net.minecraft.client.gui.GuiGraphicsExtractor;

/** Visual client settings, stored in config/minesino-client.json. */
public class SettingsPage extends Page {
	private float rowsY;

	@Override
	public String title() {
		return Ui.tr("minesino.nav.settings");
	}

	@Override
	protected void layout() {
		ClientSettings s = ClientSettings.get();
		rowsY = y + 4;
		toggle(0, "animations", () -> s.animations, v -> s.animations = v);
		toggle(1, "reduced_effects", () -> s.reducedEffects, v -> s.reducedEffects = v);
		toggle(2, "sounds", () -> s.sounds, v -> s.sounds = v);
		toggle(3, "hud_balance", () -> s.showHudBalance, v -> s.showHudBalance = v);
		toggle(4, "compact_numbers", () -> s.compactHudNumbers, v -> s.compactHudNumbers = v);
		toggle(5, "toasts", () -> s.showToasts, v -> s.showToasts = v);
		Slider speed = add(new Slider());
		speed.value = (s.animationSpeed - 0.5f) / 1.5f;
		speed.onChange = v -> {
			s.animationSpeed = (float) (0.5 + v * 1.5);
			s.save();
		};
		speed.bounds(x + w - 120, rowsY + 6 * 20 + 3, 110, 10);
	}

	private void toggle(int i, String key, BooleanSupplier get, Consumer<Boolean> set) {
		add(new Button(() -> Ui.tr(get.getAsBoolean() ? "minesino.ui.on" : "minesino.ui.off"), Button.Style.CHIP, () -> {
			set.accept(!get.getAsBoolean());
			ClientSettings.get().save();
		}).select(get)).bounds(x + w - 60, rowsY + i * 20, 50, 15);
	}

	@Override
	protected void renderContent(GuiGraphicsExtractor g, int mx, int my, float dt) {
		String[] keys = {"animations", "reduced_effects", "sounds", "hud_balance", "compact_numbers", "toasts", "animation_speed"};
		for (int i = 0; i < keys.length; i++) {
			float ry = rowsY + i * 20;
			Ui.round(g, x, ry - 2, w, 19, 5, i % 2 == 0 ? 0xFF120A0C : 0xFF231618);
			Ui.text(g, Ui.tr("minesino.settings." + keys[i]), x + 8, ry + 2, Theme.TEXT);
			Ui.text(g, Ui.fit(Ui.tr("minesino.settings." + keys[i] + ".desc"), w - 200), x + 150, ry + 2, Theme.MUTED);
		}
		Ui.right(g, String.format(java.util.Locale.ROOT, "×%.2f", ClientSettings.get().animationSpeed), x + w - 126, rowsY + 6 * 20 + 2, Theme.GOLD);
		float iy = rowsY + 7 * 20 + 8;
		Ui.text(g, Ui.tr("minesino.settings.key_hint", MineSinoClient.tabletKeyName()), x + 8, iy, Theme.TEXT_DIM);
		Ui.text(g, Ui.tr("minesino.settings.mob_hint"), x + 8, iy + 12, Theme.TEXT_DIM);
	}
}
