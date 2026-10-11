package dev.minesino.mixin;

import dev.minesino.mobs.MobSaleService;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.AgeableMob;
import net.minecraft.world.entity.animal.Animal;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Marks animals born from breeding so breeding farms cannot sell them at full price. */
@Mixin(Animal.class)
public abstract class AnimalMixin {
	@Inject(method = "finalizeSpawnChildFromBreeding", at = @At("HEAD"))
	private void minesino$tagBred(ServerLevel level, Animal partner, @Nullable AgeableMob offspring, CallbackInfo ci) {
		if (offspring != null) offspring.addTag(MobSaleService.BRED_TAG);
	}
}
