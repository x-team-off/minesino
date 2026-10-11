package dev.minesino.mobs;

import dev.minesino.config.MineSinoConfig;
import dev.minesino.pricing.Rational;

/**
 * Pure decision logic for selling an entity. The Minecraft side collects {@link MobFacts} from the
 * real server entity; this class decides whether it may be sold and for how much.
 */
public final class MobSaleRules {
	private MobSaleRules() {}

	/** Snapshot of the server-side state of the targeted entity. */
	public record MobFacts(
		String typeId,
		boolean isPlayer,
		boolean isMob,
		boolean alive,
		boolean removed,
		boolean customNamed,
		boolean tamed,
		boolean leashed,
		boolean hasPassengers,
		boolean isPassenger,
		boolean baby,
		boolean villagerLike,
		boolean bred,
		Boolean playerCreatedGolem,
		boolean protectedTag
	) {}

	public enum Denial {
		NONE, PLAYER, NOT_A_MOB, DEAD, BANNED, NO_PRICE, NAMED, TAMED, LEASHED, RIDING, BABY, VILLAGER, PROTECTED, DISABLED
	}

	public record Decision(Denial denial, long price) {
		public boolean allowed() { return denial == Denial.NONE; }
	}

	public static Decision evaluate(MobFacts f, MobPriceRegistry prices, MineSinoConfig.Mobs cfg) {
		if (!cfg.enabled) return deny(Denial.DISABLED);
		if (f.isPlayer()) return deny(Denial.PLAYER);
		if (!f.isMob()) return deny(Denial.NOT_A_MOB);
		if (!f.alive() || f.removed()) return deny(Denial.DEAD);
		if (prices.isBanned(f.typeId())) return deny(Denial.BANNED);
		if (f.protectedTag()) return deny(Denial.PROTECTED);
		if (cfg.protectNamed && f.customNamed()) return deny(Denial.NAMED);
		if (cfg.protectTamed && f.tamed()) return deny(Denial.TAMED);
		if (cfg.protectLeashed && f.leashed()) return deny(Denial.LEASHED);
		if (cfg.protectWithPassengers && (f.hasPassengers() || f.isPassenger())) return deny(Denial.RIDING);
		if (!cfg.allowBabies && f.baby()) return deny(Denial.BABY);
		if (cfg.protectVillagers && f.villagerLike()) return deny(Denial.VILLAGER);
		Rational base = prices.price(f.typeId());
		if (base == null) return deny(Denial.NO_PRICE);
		Rational p = base;
		if (f.bred()) p = p.multiply(cfg.bredMultiplier);
		if (Boolean.FALSE.equals(f.playerCreatedGolem())) p = p.multiply(cfg.naturalGolemMultiplier);
		long units = p.floor();
		if (units <= 0) return deny(Denial.NO_PRICE);
		return new Decision(Denial.NONE, units);
	}

	private static Decision deny(Denial d) {
		return new Decision(d, 0);
	}
}
