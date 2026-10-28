package dev.minesino.client.gui.widget;

import dev.minesino.client.gui.Theme;
import dev.minesino.client.gui.Ui;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.world.item.ItemStack;

/** Rounded button with hover glow, press feedback and several visual styles. */
public class Button extends Widget {
	public enum Style { PRIMARY, GOLD, GHOST, DANGER, SUCCESS, CHIP, ICON }

	private final Supplier<String> label;
	private final Runnable action;
	public Style style;
	public BooleanSupplier selected = () -> false;
	public BooleanSupplier active = () -> true;
	public ItemStack icon = ItemStack.EMPTY;
	public String tooltip;
	private long pressedAt;

	public Button(Supplier<String> label, Style style, Runnable action) {
		this.label = label;
		this.style = style;
		this.action = action;
	}

	public static Button of(String label, Style style, Runnable action) {
		return new Button(() -> label, style, action);
	}

	public Button select(BooleanSupplier s) {
		this.selected = s;
		return this;
	}

	public Button when(BooleanSupplier a) {
		this.active = a;
		return this;
	}

	public Button icon(ItemStack s) {
		this.icon = s;
		return this;
	}

	public Button tip(String t) {
		this.tooltip = t;
		return this;
	}

	@Override
	public void render(GuiGraphicsExtractor g, int mx, int my, float dt) {
		if (!visible) return;
		enabled = active.getAsBoolean();
		updateHover(mx, my, dt);
		boolean sel = selected.getAsBoolean();
		float press = Math.max(0, 1 - (System.currentTimeMillis() - pressedAt) / 150f);
		float r = style == Style.CHIP ? h / 2 : Math.min(5, h / 2);
		float inset = press * 0.6f;
		float bx = x + inset, by = y + inset, bw = w - inset * 2, bh = h - inset * 2;
		int top, bottom, border, text;
		switch (style) {
			case PRIMARY -> {
				top = Theme.lerp(0xFFFF2D4A, 0xFFFF4D66, hover);
				bottom = Theme.lerp(Theme.ACCENT_DARK, 0xFFE0102E, hover);
				border = 0xFFFF7A8C;
				text = Theme.TEXT;
			}
			case GOLD -> { // main call to action: neon red
				top = Theme.lerp(0xFFFF2D4A, 0xFFFF5A70, hover);
				bottom = Theme.lerp(0xFFD0102C, 0xFFFF1F3D, hover);
				border = 0xFFFF8FA0;
				text = 0xFFFFFFFF;
			}
			case DANGER -> {
				top = Theme.lerp(0xFFFF4D66, 0xFFFF8585, hover);
				bottom = Theme.RED_DARK;
				border = 0xFFFCA5A5;
				text = Theme.TEXT;
			}
			case SUCCESS -> {
				top = Theme.lerp(0xFF34D372, 0xFF4ADE80, hover);
				bottom = Theme.GREEN_DARK;
				border = 0xFF86EFAC;
				text = 0xFF04140A;
			}
			default -> {
				int base = sel ? Theme.CARD_PRESS : Theme.CARD;
				top = Theme.lerp(base, Theme.CARD_HI, hover);
				bottom = Theme.lerp(base, Theme.CARD_HI, hover * 0.7f);
				border = sel ? Theme.ACCENT : Theme.lerp(Theme.BORDER, Theme.BORDER_HI, hover);
				text = sel ? Theme.TEXT : Theme.lerp(Theme.TEXT_DIM, Theme.TEXT, hover);
			}
		}
		if (!enabled) {
			top = Theme.lerp(top, Theme.SURFACE, 0.7f);
			bottom = Theme.lerp(bottom, Theme.SURFACE, 0.7f);
			border = Theme.BORDER;
			text = Theme.MUTED;
		}
		boolean neon = style == Style.GOLD || style == Style.PRIMARY;
		if (enabled && (hover > 0.01f || sel || neon) && style != Style.GHOST && style != Style.CHIP && style != Style.ICON) {
			Ui.glow(g, bx, by, bw, bh, 5 + 3 * hover, Theme.alpha(neon ? Theme.ACCENT : style == Style.DANGER ? Theme.RED
				: style == Style.SUCCESS ? Theme.GREEN : Theme.ACCENT, (neon ? 0.45f : 0.25f) + 0.3f * hover));
		}
		if (sel && (style == Style.GHOST || style == Style.CHIP)) Ui.glow(g, bx, by, bw, bh, 4, Theme.alpha(Theme.ACCENT, 0.35f));
		Ui.round(g, bx, by, bw, bh, r, border);
		float b = 0.5f;
		Ui.round(g, bx + b, by + b, bw - 2 * b, bh - 2 * b, Math.max(0, r - b), bottom);
		Ui.round(g, bx + b, by + b, bw - 2 * b, (bh - 2 * b) * 0.55f, Math.max(0, r - b), top);
		if (style != Style.GHOST && style != Style.CHIP && style != Style.ICON) {
			Ui.gradient(g, bx + b, by + bh * 0.5f - 1, bw - 2 * b, bh * 0.25f, top, bottom);
		}
		String s = label.get();
		float textY = by + (bh - 8) / 2f + 0.5f;
		if (!icon.isEmpty()) {
			float iconSize = Math.min(14, bh - 4);
			if (s == null || s.isEmpty()) {
				Ui.item(g, icon, bx + (bw - iconSize) / 2, by + (bh - iconSize) / 2, iconSize);
			} else {
				float total = iconSize + 3 + Ui.width(s);
				float sx = bx + (bw - total) / 2;
				Ui.item(g, icon, sx, by + (bh - iconSize) / 2, iconSize);
				Ui.text(g, Ui.fit(s, bw - iconSize - 6), sx + iconSize + 3, textY, text);
			}
		} else if (s != null) {
			Ui.center(g, Ui.fit(s, bw - 4), bx + bw / 2, textY, text);
		}
		if (tooltip != null && hover > 0.9f) {
			g.setTooltipForNextFrame(Ui.font(), net.minecraft.network.chat.Component.literal(tooltip), mx, my);
		}
	}

	@Override
	public boolean mouseClicked(double mx, double my, int button) {
		if (!visible || !enabled || button != Ui.LEFT || !isOver(mx, my)) return false;
		pressedAt = System.currentTimeMillis();
		Ui.clickSound();
		action.run();
		return true;
	}
}
