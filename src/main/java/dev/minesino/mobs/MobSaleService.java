package dev.minesino.mobs;

import com.google.gson.JsonObject;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import dev.minesino.server.MineSinoServer;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.Leashable;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.TamableAnimal;
import net.minecraft.world.entity.animal.equine.AbstractHorse;
import net.minecraft.world.entity.animal.golem.IronGolem;
import net.minecraft.world.entity.npc.villager.AbstractVillager;
import net.minecraft.world.entity.player.Player;

/**
 * Shift + right click mob selling. Flow: request → server validation → offer (entity locked to
 * that offer) → confirmation → re-validation → payment keyed by the entity UUID → entity removed.
 * Because the payment operation id is {@code mob:<entity uuid>}, one entity can never be paid twice,
 * even across restarts, concurrent requests or forged packets.
 */
public final class MobSaleService {
	public static final String BRED_TAG = "minesino.bred";
	public static final String PROTECTED_TAG = "minesino.protected";

	private record Offer(long id, UUID player, UUID entity, long price, long expiresAt, String typeId) {}

	private final MineSinoServer ms;
	private final AtomicLong ids = new AtomicLong(System.nanoTime() & 0xFFFFFFFFL);
	private final Map<Long, Offer> offers = new HashMap<>();
	private final Map<UUID, Long> lockedEntities = new HashMap<>();

	public MobSaleService(MineSinoServer ms) {
		this.ms = ms;
	}

	public static MobSaleRules.MobFacts facts(Entity e) {
		String type = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
		boolean tamed = (e instanceof TamableAnimal t && t.isTame()) || (e instanceof AbstractHorse h && h.isTamed());
		boolean leashed = e instanceof Leashable l && l.isLeashed();
		boolean baby = e instanceof LivingEntity le && le.isBaby();
		Boolean golem = e instanceof IronGolem g ? g.isPlayerCreated() : null;
		return new MobSaleRules.MobFacts(type, e instanceof Player, e instanceof Mob, e.isAlive(), e.isRemoved(),
			e.hasCustomName(), tamed, leashed, e.isVehicle(), e.isPassenger(), baby, e instanceof AbstractVillager,
			e.entityTags().contains(BRED_TAG), golem,
			e.entityTags().contains(PROTECTED_TAG));
	}

	public record Result(String error, JsonObject data) {
		static Result fail(String key) { return new Result(key, new JsonObject()); }
	}

	/** Step 1: player aimed at an entity (network id) with Shift + right click. */
	public Result request(ServerPlayer player, int entityNetId, String opPrefix) {
		var cfg = ms.config().mobs;
		if (!cfg.enabled) return Result.fail("minesino.error.mob_sales_disabled");
		Entity e = player.level().getEntity(entityNetId);
		if (e == null) return Result.fail("minesino.error.mob_gone");
		if (e.level() != player.level() || player.distanceToSqr(e) > cfg.maxDistance * cfg.maxDistance) return Result.fail("minesino.error.mob_too_far");
		MobSaleRules.Decision d = MobSaleRules.evaluate(facts(e), ms.mobPrices(), cfg);
		if (!d.allowed()) return Result.fail("minesino.mob.deny." + d.denial().name().toLowerCase(java.util.Locale.ROOT));
		if (ms.economy.isApplied("mob:" + e.getUUID())) return Result.fail("minesino.error.mob_already_sold");
		Long lockedBy = lockedEntities.get(e.getUUID());
		if (lockedBy != null && offers.containsKey(lockedBy) && !offers.get(lockedBy).player().equals(player.getUUID())) {
			return Result.fail("minesino.error.mob_busy");
		}
		cancelOffers(player.getUUID());
		String typeId = BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString();
		if (!cfg.requireConfirmation) {
			return execute(player, e, d.price(), typeId);
		}
		Offer o = new Offer(ids.incrementAndGet(), player.getUUID(), e.getUUID(), d.price(),
			ms.ticks() + cfg.offerTimeoutSeconds * 20L, typeId);
		offers.put(o.id(), o);
		lockedEntities.put(e.getUUID(), o.id());
		JsonObject data = new JsonObject();
		data.addProperty("offer", o.id());
		data.addProperty("entity", typeId);
		data.addProperty("entityNetId", e.getId());
		data.addProperty("name", e.getDisplayName().getString());
		data.addProperty("price", d.price());
		data.addProperty("timeout", cfg.offerTimeoutSeconds);
		return new Result(null, data);
	}

	/** Step 2: the player confirmed the offer. Everything is checked again. */
	public Result confirm(ServerPlayer player, long offerId) {
		Offer o = offers.remove(offerId);
		if (o == null || !o.player().equals(player.getUUID())) return Result.fail("minesino.error.offer_expired");
		lockedEntities.remove(o.entity());
		if (ms.ticks() > o.expiresAt()) return Result.fail("minesino.error.offer_expired");
		if (!(player.level() instanceof ServerLevel level)) return Result.fail("minesino.error.mob_gone");
		Entity e = level.getEntity(o.entity());
		if (e == null) return Result.fail("minesino.error.mob_gone");
		var cfg = ms.config().mobs;
		if (player.distanceToSqr(e) > cfg.maxDistance * cfg.maxDistance * 4) return Result.fail("minesino.error.mob_too_far");
		MobSaleRules.Decision d = MobSaleRules.evaluate(facts(e), ms.mobPrices(), cfg);
		if (!d.allowed()) return Result.fail("minesino.mob.deny." + d.denial().name().toLowerCase(java.util.Locale.ROOT));
		if (d.price() != o.price()) return Result.fail("minesino.error.price_changed");
		return execute(player, e, d.price(), o.typeId());
	}

	private Result execute(ServerPlayer player, Entity e, long price, String typeId) {
		TxResult r = ms.economy.deposit(player.getUUID(), price, TxType.MOB_SALE, typeId, "mob:" + e.getUUID());
		if (!r.ok()) {
			return Result.fail(r.status() == TxResult.Status.DUPLICATE ? "minesino.error.mob_already_sold" : "minesino.error.transaction_failed");
		}
		// Paid exactly once (keyed by UUID); remove the entity in the same tick without drops.
		e.discard();
		ms.economy.updateStats(player.getUUID(), s -> {
			s.mobsSold++;
			s.mobSaleIncome += price;
		});
		JsonObject data = new JsonObject();
		data.addProperty("sold", true);
		data.addProperty("entity", typeId);
		data.addProperty("price", price);
		return new Result(null, data);
	}

	public void cancel(ServerPlayer player, long offerId) {
		Offer o = offers.get(offerId);
		if (o != null && o.player().equals(player.getUUID())) {
			offers.remove(offerId);
			lockedEntities.remove(o.entity());
		}
	}

	public void cancelOffers(UUID player) {
		Iterator<Offer> it = offers.values().iterator();
		while (it.hasNext()) {
			Offer o = it.next();
			if (o.player().equals(player)) {
				it.remove();
				lockedEntities.remove(o.entity());
			}
		}
	}

	public void expireOffers() {
		long now = ms.ticks();
		Iterator<Offer> it = offers.values().iterator();
		while (it.hasNext()) {
			Offer o = it.next();
			if (now > o.expiresAt()) {
				it.remove();
				lockedEntities.remove(o.entity());
			}
		}
	}
}
