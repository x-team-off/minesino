package dev.minesino.pricing;

/** Price categories, in the order the shop and selling screens list them. */
public enum PriceCategory {
	WOOD,
	STONE,
	ORES,
	RARE,
	FOOD,
	FARMING,
	MOB_DROPS,
	PLANTS,
	DECORATIVE,
	TOOLS,
	ARMOR,
	SPECIAL,
	FORBIDDEN;

	public static PriceCategory parse(String s, PriceCategory def) {
		try {
			return valueOf(s.trim().toUpperCase(java.util.Locale.ROOT));
		} catch (RuntimeException e) {
			return def;
		}
	}

	public String translationKey() {
		return "minesino.category." + name().toLowerCase(java.util.Locale.ROOT);
	}
}
