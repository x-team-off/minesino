package dev.minesino.pricing;

import java.util.List;

/**
 * A crafting step reduced to what pricing needs: {@code count} of {@code output} are made from one
 * item out of each ingredient slot. Each slot lists its interchangeable alternatives
 * (for example any plank type), and the cheapest priced alternative is used.
 */
public record RecipeEdge(String output, int count, List<List<String>> slots, String source) {
	public RecipeEdge {
		if (count <= 0) throw new IllegalArgumentException("count must be positive");
		slots = List.copyOf(slots);
	}
}
