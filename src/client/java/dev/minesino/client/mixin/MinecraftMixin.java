package dev.minesino.client.mixin;

import dev.minesino.client.ClientState;
import dev.minesino.client.MineSinoClient;
import net.minecraft.client.Minecraft;
import net.minecraft.world.phys.EntityHitResult;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Input handling for the tenth slot: right click uses the tablet instead of the hidden hotbar item,
 * attacking/mining is disabled while the tablet is held, and pressing 1..9 leaves the tablet slot.
 * Right clicks with other items are untouched.
 */
@Mixin(Minecraft.class)
public abstract class MinecraftMixin {
	@Shadow
	private int rightClickDelay;

	@Inject(method = "startUseItem", at = @At("HEAD"), cancellable = true)
	private void minesino$useTablet(CallbackInfo ci) {
		Minecraft mc = (Minecraft) (Object) this;
		if (mc.player == null) return;
		if (ClientState.tabletActive()) {
			ci.cancel();
			this.rightClickDelay = 4;
			MineSinoClient.useTablet(mc);
			return;
		}
		// Optional server setting: Shift + right click with an empty hand sells without the tablet.
		if (!ClientState.mobRequireTablet && mc.player.isShiftKeyDown() && mc.player.getMainHandItem().isEmpty()
			&& mc.hitResult instanceof EntityHitResult e) {
			ci.cancel();
			this.rightClickDelay = 4;
			MineSinoClient.requestMobSale(e.getEntity().getId());
		}
	}

	@Inject(method = "startAttack", at = @At("HEAD"), cancellable = true)
	private void minesino$noAttack(CallbackInfoReturnable<Boolean> cir) {
		if (ClientState.tabletActive()) cir.setReturnValue(false);
	}

	@Inject(method = "continueAttack", at = @At("HEAD"), cancellable = true)
	private void minesino$noMining(boolean down, CallbackInfo ci) {
		if (ClientState.tabletActive()) ci.cancel();
	}

	@Inject(method = "handleKeybinds", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/player/Inventory;setSelectedSlot(I)V"))
	private void minesino$leaveTabletSlot(CallbackInfo ci) {
		MineSinoClient.selectTablet(false);
	}
}
