package dev.minesino.client.gui.widget;

import dev.minesino.client.gui.Ui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;

/** Minimal retained-mode widget used by all MineSino screens. */
public abstract class Widget {
	public float x, y, w, h;
	public boolean visible = true;
	public boolean enabled = true;
	protected float hover;

	public Widget bounds(float x, float y, float w, float h) {
		this.x = x;
		this.y = y;
		this.w = w;
		this.h = h;
		return this;
	}

	public boolean isOver(double mx, double my) {
		return visible && Ui.inside(mx, my, x, y, w, h);
	}

	protected void updateHover(double mx, double my, float dt) {
		hover = dev.minesino.client.gui.Anim.approach(hover, enabled && isOver(mx, my) ? 1f : 0f, dt, 14f);
	}

	public abstract void render(GuiGraphicsExtractor g, int mx, int my, float dt);

	public boolean mouseClicked(double mx, double my, int button) {
		return false;
	}

	public boolean mouseReleased(double mx, double my, int button) {
		return false;
	}

	public boolean mouseDragged(double mx, double my, int button) {
		return false;
	}

	public boolean mouseScrolled(double mx, double my, double amount) {
		return false;
	}

	public boolean keyPressed(KeyEvent e) {
		return false;
	}

	public boolean charTyped(CharacterEvent e) {
		return false;
	}

	public boolean isFocused() {
		return false;
	}

	public void setFocused(boolean f) {}
}
