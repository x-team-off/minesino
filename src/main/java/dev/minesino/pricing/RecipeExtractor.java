package dev.minesino.pricing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.minecraft.core.Holder;
import net.minecraft.core.component.DataComponents;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingInput;
import net.minecraft.world.item.crafting.Ingredient;
import net.minecraft.world.item.crafting.Recipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.item.crafting.RecipeManager;
import net.minecraft.world.item.crafting.ShapedRecipe;
import net.minecraft.world.item.crafting.ShapelessRecipe;
import net.minecraft.world.item.crafting.SingleItemRecipe;
import net.minecraft.world.item.crafting.SmithingRecipeInput;
import net.minecraft.world.item.crafting.SmithingTransformRecipe;
import net.minecraft.world.item.crafting.StonecutterRecipe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Converts the server's loaded recipes (vanilla plus datapacks) into {@link RecipeEdge}s.
 * Only deterministic, input-consuming recipe kinds are used: shaped and shapeless crafting,
 * stonecutting and smithing transforms. Recipes whose ingredients leave a remainder (buckets,
 * bottles) and special recipes (dyeing armor, fireworks...) are skipped, because ignoring the
 * remainder would overvalue the output.
 */
public final class RecipeExtractor {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Pricing");

	private RecipeExtractor() {}

	public static List<RecipeEdge> extract(RecipeManager manager) {
		List<RecipeEdge> edges = new ArrayList<>();
		int skipped = 0;
		for (RecipeHolder<?> holder : manager.getRecipes()) {
			try {
				RecipeEdge e = convert(holder.value(), holder.id().identifier().toString());
				if (e != null) edges.add(e);
				else skipped++;
			} catch (RuntimeException ex) {
				skipped++;
			}
		}
		LOG.info("Pricing uses {} recipes ({} special/unsupported skipped)", edges.size(), skipped);
		return edges;
	}

	private static RecipeEdge convert(Recipe<?> recipe, String source) {
		List<List<String>> slots = new ArrayList<>();
		ItemStack out;
		if (recipe instanceof ShapedRecipe shaped) {
			for (Optional<Ingredient> ing : shaped.getIngredients()) {
				if (ing.isEmpty()) continue;
				if (!addSlot(slots, ing.get())) return null;
			}
			out = shaped.assemble(CraftingInput.EMPTY);
		} else if (recipe instanceof ShapelessRecipe shapeless) {
			for (Ingredient ing : shapeless.placementInfo().ingredients()) {
				if (!addSlot(slots, ing)) return null;
			}
			out = shapeless.assemble(CraftingInput.EMPTY);
		} else if (recipe instanceof StonecutterRecipe cut) {
			if (!addSlot(slots, cut.input())) return null;
			out = ((SingleItemRecipe) cut).assemble(null);
		} else if (recipe instanceof SmithingTransformRecipe smith) {
			if (smith.templateIngredient().isPresent() && !addSlot(slots, smith.templateIngredient().get())) return null;
			if (!addSlot(slots, smith.baseIngredient())) return null;
			if (smith.additionIngredient().isPresent() && !addSlot(slots, smith.additionIngredient().get())) return null;
			Item base = smith.baseIngredient().items().findFirst().map(Holder::value).orElse(null);
			if (base == null) return null;
			out = smith.assemble(new SmithingRecipeInput(ItemStack.EMPTY, new ItemStack(base), ItemStack.EMPTY));
		} else {
			return null;
		}
		if (out == null || out.isEmpty() || slots.isEmpty()) return null;
		return new RecipeEdge(BuiltInRegistries.ITEM.getKey(out.getItem()).toString(), out.getCount(), slots, source);
	}

	private static boolean addSlot(List<List<String>> slots, Ingredient ing) {
		List<String> alts = new ArrayList<>();
		for (Holder<Item> h : ing.items().toList()) {
			Item item = h.value();
			if (item.getCraftingRemainder() != null) return false;
			alts.add(BuiltInRegistries.ITEM.getKey(item).toString());
		}
		if (alts.isEmpty()) return false;
		slots.add(alts);
		return true;
	}

	/** Category for derived items: tools, armor and blocks are recognised from item data. */
	public static PriceCategory guessCategory(String id) {
		Item item = BuiltInRegistries.ITEM.getValue(net.minecraft.resources.Identifier.parse(id));
		if (item == null) return null;
		ItemStack s = new ItemStack(item);
		if (s.has(DataComponents.EQUIPPABLE) && s.has(DataComponents.MAX_DAMAGE)) return PriceCategory.ARMOR;
		if (s.has(DataComponents.TOOL) || s.has(DataComponents.WEAPON)) return PriceCategory.TOOLS;
		if (item instanceof BlockItem && (id.endsWith("_carpet") || id.contains("stained_glass") || id.endsWith("_bed")
			|| id.endsWith("_banner") || id.endsWith("_candle") || id.endsWith("_terracotta"))) return PriceCategory.DECORATIVE;
		return null; // inherit the category of the main raw material
	}
}
