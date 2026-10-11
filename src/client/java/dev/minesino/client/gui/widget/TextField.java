package dev.minesino.client.gui.widget;

import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import java.util.function.Consumer;
import java.util.function.Predicate;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import com.mojang.blaze3d.platform.InputConstants;

/** Single-line text input with caret, placeholder and character filter. */
public class TextField extends Widget {
	private String text = "";
	private boolean focused;
	public String placeholder = "";
	public String suffix = "";
	public int maxLength = 24;
	public Predicate<Character> allowed = c -> true;
	public Consumer<String> onChange = s -> {};
	public Runnable onEnter = () -> {};

	public String text() {
		return text;
	}

	public void setText(String t) {
		this.text = t.length() > maxLength ? t.substring(0, maxLength) : t;
	}

	@Override
	public boolean isFocused() {
		return focused;
	}

	/**
	 * 26.3 delivers typed characters only while a text input owner is registered with the
	 * TextInputManager (SDL text input), so focus changes are reported there like vanilla EditBox does.
	 */
	@Override
	public void setFocused(boolean f) {
		if (f == focused) return;
		focused = f;
		net.minecraft.client.Minecraft.getInstance().textInputManager().onTextInputFocusChange(this, f);
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (!visible) return;
		updateHover(mx, my, dt);
		int border = focused ? Theme.PURPLE : Theme.lerp(Theme.BORDER, Theme.BORDER_HI, hover);
		if (focused) Ui.glow(g, x, y, w, h, 4, Theme.alpha(Theme.PURPLE, 0.35f));
		Ui.panel(g, x, y, w, h, 4, Theme.BG, border);
		float ty = y + (h - 8) / 2f + 0.5f;
		float sw = suffix.isEmpty() ? 0 : Ui.width(suffix) + 4;
		if (text.isEmpty() && !focused) {
			Ui.text(g, Ui.fit(placeholder, w - 8 - sw), x + 5, ty, Theme.MUTED);
		} else {
			String shown = Ui.width(text) > w - 10 - sw ? Ui.font().plainSubstrByWidth(text, (int) (w - 10 - sw), true) : text;
			Ui.text(g, shown, x + 5, ty, Theme.TEXT);
			if (focused && (System.currentTimeMillis() / 500) % 2 == 0) {
				Ui.rect(g, x + 5 + Ui.width(shown) + 0.5f, ty - 1, 1, 10, Theme.GOLD);
			}
		}
		if (!suffix.isEmpty()) Ui.right(g, suffix, x + w - 5, ty, Theme.MUTED);
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		setFocused(isOver(mx, my));
		return focused;
	}

	@Override
	public boolean keyPressed(KeyEvent e) {
		if (!focused) return false;
		int k = e.key();
		if (k == InputConstants.KEY_BACKSPACE) {
			if (!text.isEmpty()) {
				text = e.hasControlDown() ? "" : text.substring(0, text.length() - 1);
				onChange.accept(text);
			}
			return true;
		}
		if (k == InputConstants.KEY_RETURN || k == InputConstants.KEY_NUMPADENTER) {
			onEnter.run();
			return true;
		}
		if (k == InputConstants.KEY_ESCAPE) {
			setFocused(false);
			return true;
		}
		// Swallow other keys so typing never triggers game keybinds.
		return k != InputConstants.KEY_TAB;
	}

	@Override
	public boolean charTyped(CharacterEvent e) {
		if (!focused) return false;
		String s = e.codepointAsString();
		if (s.length() != 1 || !allowed.test(s.charAt(0)) || text.length() >= maxLength) return true;
		text += s;
		onChange.accept(text);
		return true;
	}
}
