package dev.minesino.client.mixin;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import dev.minesino.client.ClientState;
import dev.minesino.tablet.TabletItems;
import net.minecraft.client.player.FirstPersonHandsAndItems;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.world.item.ItemStack;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;

/** Shows the tablet in the first-person main hand while the tenth slot is selected. */
@Mixin(FirstPersonHandsAndItems.class)
public abstract class FirstPersonHandsAndItemsMixin {
	@Unique
	private static ItemStack minesino$tablet;

	@WrapOperation(method = "tick", at = @At(value = "INVOKE",
		target = "Lnet/minecraft/client/player/LocalPlayer;getMainHandItem()Lnet/minecraft/world/item/ItemStack;"))
	private ItemStack minesino$tabletInHand(LocalPlayer player, Operation<ItemStack> original) {
		if (ClientState.tabletActive()) {
			if (minesino$tablet == null) minesino$tablet = TabletItems.displayStack();
			return minesino$tablet;
		}
		return original.call(player);
	}
}
