package dev.minesino;

import static org.junit.jupiter.api.Assertions.*;

import dev.minesino.config.ConfigIO;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.TxType;
import dev.minesino.server.AdminAccess;
import dev.minesino.server.BalanceSyncTracker;
import dev.minesino.server.RequestGuard;
import dev.minesino.tablet.TabletState;
import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerLogicTest {
	private final UUID p = UUID.fromString("00000000-0000-0000-0000-000000000123");

	@Test
	void replayedRequestsAreRejected() {
		RequestGuard g = new RequestGuard();
		assertEquals(RequestGuard.Verdict.OK, g.check(p, 77, 100, 0, 2, 10));
		assertEquals(RequestGuard.Verdict.REPLAY, g.check(p, 77, 200, 5000, 2, 10));
		assertEquals(RequestGuard.Verdict.TOO_FAST, g.check(p, 78, 101, 50, 2, 10));
		assertEquals(RequestGuard.Verdict.OK, g.check(p, 78, 103, 100, 2, 10));
		assertEquals(RequestGuard.Verdict.INVALID, g.check(p, 0, 300, 9000, 2, 10));
	}

	@Test
	void floodIsLimitedPerSecond() {
		RequestGuard g = new RequestGuard();
		int ok = 0;
		for (int i = 1; i <= 50; i++) if (g.check(p, i, i * 10L, 10, 0, 12) == RequestGuard.Verdict.OK) ok++;
		assertEquals(12, ok);
	}

	@Test
	void balanceSyncSendsLatestValueOncePerTick() {
		EconomyManager eco = new EconomyManager(0, 50);
		BalanceSyncTracker sync = new BalanceSyncTracker();
		eco.addListener(sync::changed);
		eco.getOrCreate(p, "P");
		eco.deposit(p, 100, TxType.ADMIN_ADD, "", "a");
		eco.deposit(p, 50, TxType.ADMIN_ADD, "", "b");
		eco.withdraw(p, 30, TxType.BET, "", "c");
		Map<UUID, Long> sent = sync.drain();
		assertEquals(Map.of(p, 120L), sent);
		assertTrue(sync.drain().isEmpty(), "no packet when nothing changed");
		sync.forceSync(p, 120);
		assertEquals(Map.of(p, 120L), sync.drain(), "login always resends");
	}

	@Test
	void commandPermissions() {
		MineSinoConfig.Permissions cfg = new MineSinoConfig.Permissions();
		assertFalse(AdminAccess.isAdmin(p, 0, cfg), "regular players cannot change balances");
		assertTrue(AdminAccess.isAdmin(p, 2, cfg));
		assertTrue(AdminAccess.isAdmin(null, 4, cfg), "console");
		assertFalse(AdminAccess.isAdmin(null, 0, cfg));
		cfg.adminPlayers.add(p.toString());
		assertTrue(AdminAccess.isAdmin(p, 0, cfg));
	}

	@Test
	void tabletCannotBeDuplicated() {
		TabletState t = new TabletState();
		assertTrue(t.select(p, true));
		assertFalse(t.select(p, true), "pressing 0 again changes nothing");
		for (int i = 0; i < 100; i++) t.select(p, i % 2 == 0);
		assertEquals(1, t.tabletCount(p));
		t.forget(p); // disconnect
		t.reset(p); // rejoin
		assertFalse(t.isSelected(p));
		assertEquals(1, t.tabletCount(p));
	}

	@Test
	void invalidConfigFallsBackToSafeValues() {
		MineSinoConfig c = new MineSinoConfig();
		c.currency.displayDecimals = 7;
		c.casino.minBet = new java.math.BigDecimal("-5");
		c.shop.buyMultiplier = new java.math.BigDecimal("0.5");
		c.casino.slots.symbols.get(0).weight = 99;
		List<String> warnings = new ArrayList<>();
		c.validate(warnings::add);
		assertEquals(2, c.currency.displayDecimals);
		assertEquals(0, c.casino.minBet.compareTo(java.math.BigDecimal.ONE));
		assertTrue(c.shop.buyMultiplier.compareTo(c.selling.sellMultiplier) >= 0);
		assertEquals(32, c.casino.slots.symbols.stream().mapToInt(s -> s.weight).sum());
		assertTrue(warnings.size() >= 4);
	}

	@Test
	void configWithCommentsLoadsAndMissingFileIsCreated(@TempDir Path dir) throws Exception {
		Path f = dir.resolve("minesino.json");
		MineSinoConfig loaded = ConfigIO.load(f, MineSinoConfig.class, new MineSinoConfig(), ConfigIO.mainComments(), w -> fail(w));
		assertNotNull(loaded);
		String text = Files.readString(f);
		assertTrue(text.contains("// Currency symbol"), "written file is documented");
		MineSinoConfig again = ConfigIO.GSON.fromJson(ConfigIO.parseLenient(new StringReader(text)), MineSinoConfig.class);
		assertEquals("MS", again.currency.symbol);
		Files.writeString(f, "{ this is not json");
		List<String> w = new ArrayList<>();
		MineSinoConfig fallback = ConfigIO.load(f, MineSinoConfig.class, new MineSinoConfig(), ConfigIO.mainComments(), w::add);
		assertEquals("MS", fallback.currency.symbol);
		assertFalse(w.isEmpty());
		assertTrue(Files.readString(f).startsWith("{ this"), "broken file is not overwritten");
	}
}
