package dev.minesino.tablet;

import dev.minesino.MineSino;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Rarity;

/**
 * The MineSino Tablet item. It exists only so the tenth slot and the first-person hand can render
 * a proper model; it is never placed in any inventory. Real stacks of it are forgeries and are
 * deleted by the server wherever they appear.
 */
public final class TabletItems {
	public static final Identifier TABLET_ID = Identifier.fromNamespaceAndPath(MineSino.MOD_ID, "tablet");
	public static final ResourceKey<Item> TABLET_KEY = ResourceKey.create(Registries.ITEM, TABLET_ID);
	public static Item TABLET;

	private TabletItems() {}

	public static void register() {
		TABLET = Registry.register(BuiltInRegistries.ITEM, TABLET_KEY,
			new Item(new Item.Properties().setId(TABLET_KEY).stacksTo(1).rarity(Rarity.EPIC).fireResistant()));
	}

	/** Display-only stack for rendering the virtual slot. */
	public static ItemStack displayStack() {
		return new ItemStack(TABLET);
	}

	public static boolean isTablet(ItemStack stack) {
		return !stack.isEmpty() && stack.is(TABLET);
	}
}
