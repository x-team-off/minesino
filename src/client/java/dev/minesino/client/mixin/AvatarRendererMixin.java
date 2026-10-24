package dev.minesino.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.minesino.client.ClientState;
import dev.minesino.tablet.TabletItems;
import net.minecraft.client.renderer.entity.player.AvatarRenderer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Avatar;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Arm pose follows the tablet instead of the hidden hotbar item (no bow/shield/spear poses). */
@Mixin(AvatarRenderer.class)
public abstract class AvatarRendererMixin {
	@Unique
	private static ItemStack minesino$tablet;

	@WrapOperation(method = "getArmPose(Lnet/minecraft/world/entity/Avatar;Lnet/minecraft/world/entity/HumanoidArm;)Lnet/minecraft/client/model/HumanoidModel$ArmPose;",
		at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/Avatar;getItemInHand(Lnet/minecraft/world/InteractionHand;)Lnet/minecraft/world/item/ItemStack;"))
	private static ItemStack minesino$tabletPose(Avatar avatar, InteractionHand hand, Operation<ItemStack> original) {
		if (hand == InteractionHand.MAIN_HAND && avatar instanceof Player player && ClientState.holdsTablet(player)) {
			if (minesino$tablet == null) minesino$tablet = TabletItems.displayStack();
			return minesino$tablet;
		}
		return original.call(avatar, hand);
	}
}
