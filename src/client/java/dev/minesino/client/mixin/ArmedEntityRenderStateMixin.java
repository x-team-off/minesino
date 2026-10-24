package dev.minesino.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.minesino.client.ClientState;
import dev.minesino.tablet.TabletItems;
import net.minecraft.client.renderer.entity.state.ArmedEntityRenderState;
import net.minecraft.world.entity.HumanoidArm;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Third person: players with the tenth slot selected (others, or yourself in F5) hold the tablet. */
@Mixin(ArmedEntityRenderState.class)
public abstract class ArmedEntityRenderStateMixin {
	@Unique
	private static ItemStack minesino$tablet;

	@WrapOperation(method = "extractArmedEntityRenderState", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/world/entity/LivingEntity;getItemHeldByArm(Lnet/minecraft/world/entity/HumanoidArm;)Lnet/minecraft/world/item/ItemStack;"))
	private static ItemStack minesino$tabletInHand(LivingEntity entity, HumanoidArm arm, Operation<ItemStack> original) {
		if (arm == entity.getMainArm() && entity instanceof Player player && ClientState.holdsTablet(player)) {
			if (minesino$tablet == null) minesino$tablet = TabletItems.displayStack();
			return minesino$tablet;
		}
		return original.call(entity, arm);
	}
}
