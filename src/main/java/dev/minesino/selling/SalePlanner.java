package dev.minesino.selling;

import dev.minesino.config.MineSinoConfig;
import dev.minesino.pricing.PriceRegistry;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Validates a sale request against the real (server-side) inventory snapshot and current prices.
 * The client only says which slots and how many; the server decides whether that is possible and
 * what it is worth. Prices sent by the client are never used.
 */
public final class SalePlanner {
	private SalePlanner() {}

	/** Server snapshot of one inventory slot. {@code modified} = has non-default components. */
	public record SlotContent(int slot, String itemId, int count, boolean modified) {}

	/** One requested line: sell {@code count} items of {@code itemId} from {@code slot}. */
	public record Line(int slot, String itemId, int count) {}

	public record PlannedLine(int slot, String itemId, int count, long value) {}

	public record Plan(List<PlannedLine> lines, long total, int itemCount, String error) {
		public boolean ok() { return error == null; }

		static Plan fail(String key) { return new Plan(List.of(), 0, 0, key); }
	}

	public static Plan plan(List<Line> request, Map<Integer, SlotContent> inventory, PriceRegistry prices, MineSinoConfig cfg) {
		if (!cfg.selling.enabled) return Plan.fail("minesino.error.selling_disabled");
		if (request.isEmpty() || request.size() > cfg.limits.maxSellLines) return Plan.fail("minesino.error.invalid_params");
		Map<Integer, Integer> usedPerSlot = new HashMap<>();
		List<PlannedLine> out = new ArrayList<>();
		long total = 0;
		int items = 0;
		for (Line l : request) {
			if (l.count() <= 0) return Plan.fail("minesino.error.invalid_params");
			SlotContent c = inventory.get(l.slot());
			if (c == null || !c.itemId().equals(l.itemId())) return Plan.fail("minesino.error.inventory_changed");
			int used = usedPerSlot.getOrDefault(l.slot(), 0) + l.count();
			if (used > c.count()) return Plan.fail("minesino.error.inventory_changed");
			usedPerSlot.put(l.slot(), used);
			if (prices.isBanned(l.itemId()) || l.itemId().equals("minesino:tablet")) return Plan.fail("minesino.error.not_sellable");
			if (c.modified() && !cfg.selling.allowModifiedItems) return Plan.fail("minesino.error.modified_item");
			if (prices.sellUnitPrice(l.itemId()) == null) return Plan.fail("minesino.error.not_sellable");
			long value = prices.sellValue(l.itemId(), l.count(), cfg.selling.sellMultiplier);
			if (value <= 0) return Plan.fail("minesino.error.not_sellable");
			try {
				total = Math.addExact(total, value);
			} catch (ArithmeticException e) {
				return Plan.fail("minesino.error.limit_exceeded");
			}
			items += l.count();
			out.add(new PlannedLine(l.slot(), l.itemId(), l.count(), value));
		}
		return new Plan(out, total, items, null);
	}

	/** Builds "sell all sellable items of this id" (or all sellable items when id is null) from the snapshot. */
	public static List<Line> allOf(String itemId, Map<Integer, SlotContent> inventory, PriceRegistry prices, MineSinoConfig cfg) {
		List<Line> lines = new ArrayList<>();
		for (SlotContent c : inventory.values()) {
			if (itemId != null && !c.itemId().equals(itemId)) continue;
			if (prices.sellUnitPrice(c.itemId()) == null || prices.isBanned(c.itemId())) continue;
			if (c.modified() && !cfg.selling.allowModifiedItems) continue;
			lines.add(new Line(c.slot(), c.itemId(), c.count()));
			if (lines.size() >= cfg.limits.maxSellLines) break;
		}
		return lines;
	}
}
