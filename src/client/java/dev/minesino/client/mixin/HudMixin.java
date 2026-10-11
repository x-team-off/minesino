package dev.minesino.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.mojang.renderpearl.api.pipeline.RenderPipeline;
import dev.minesino.client.ClientState;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.Hud;
import net.minecraft.resources.Identifier;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** While the tablet slot is selected, the vanilla selection frame and held item name are hidden. */
@Mixin(Hud.class)
public abstract class HudMixin {
	@WrapOperation(method = "extractItemHotbar", at = @At(value = "INVOKE", ordinal = 1,
		target = "Lnet/minecraft/client/gui/GuiGraphicsExtractor;blitSprite(Lcom/mojang/renderpearl/api/pipeline/RenderPipeline;Lnet/minecraft/resources/Identifier;IIII)V"))
	private void minesino$hideVanillaSelection(GuiGraphicsExtractor g, RenderPipeline pipeline, Identifier sprite, int x, int y, int w, int h,
											   Operation<Void> original) {
		if (!ClientState.tabletActive()) original.call(g, pipeline, sprite, x, y, w, h);
	}

	@Inject(method = "extractSelectedItemName", at = @At("HEAD"), cancellable = true)
	private void minesino$hideItemName(GuiGraphicsExtractor g, CallbackInfo ci) {
		if (ClientState.tabletActive()) ci.cancel();
	}
}
