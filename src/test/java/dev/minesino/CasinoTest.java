package dev.minesino;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import dev.minesino.casino.CasinoService;
import dev.minesino.casino.GameException;
import dev.minesino.casino.games.BlackjackGame;
import dev.minesino.casino.games.DiceGame;
import dev.minesino.casino.games.PlinkoGame;
import dev.minesino.casino.games.RouletteGame;
import dev.minesino.casino.games.SlotsGame;
import dev.minesino.casino.games.UpgradeGame;
import dev.minesino.config.MineSinoConfig;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.TxType;
import dev.minesino.util.Money;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CasinoTest {
	private final UUID bob = UUID.fromString("00000000-0000-0000-0000-0000000000b0");
	private final MineSinoConfig cfg = TestSupport.config();

	private EconomyManager eco() {
		EconomyManager e = new EconomyManager(Money.ofWhole(1000), 200);
		e.getOrCreate(bob, "Bob");
		return e;
	}

	// ---------------------------------------------------------------- roulette

	@Test
	void rouletteOddsMatchEuropeanWheel() {
		for (RouletteGame.BetType t : RouletteGame.BetType.values()) {
			int value = t == RouletteGame.BetType.STRAIGHT ? 17 : t == RouletteGame.BetType.DOZEN || t == RouletteGame.BetType.COLUMN ? 2 : 0;
			long returned = 0;
			for (int n = 0; n < RouletteGame.POCKETS; n++) returned += RouletteGame.payout(new RouletteGame.Bet(t, value, 100), n);
			// Expected return over all 37 equally likely pockets is exactly 36/37 of the stake.
			assertEquals(36 * 100, returned, t.name());
		}
		assertEquals(3600, RouletteGame.payout(new RouletteGame.Bet(RouletteGame.BetType.STRAIGHT, 0, 100), 0));
		assertEquals(0, RouletteGame.payout(new RouletteGame.Bet(RouletteGame.BetType.RED, 0, 100), 0), "zero loses outside bets");
		assertTrue(RouletteGame.isRed(1) && !RouletteGame.isRed(2) && !RouletteGame.isRed(0));
	}

	@Test
	void rouletteSpinChargesThenPays() throws GameException {
		EconomyManager e = eco();
		TestSupport.ScriptedRandom rnd = new TestSupport.ScriptedRandom(17);
		CasinoService c = TestSupport.casino(e, cfg, rnd);
		JsonObject p = TestSupport.json("{\"bets\":[{\"type\":\"straight\",\"value\":17,\"amount\":1000},{\"type\":\"black\",\"amount\":500}]}");
		JsonObject res = c.handle(bob, "roulette", "spin", p);
		assertEquals(17, res.get("number").getAsInt());
		// 17 is black: straight pays 35:1 (36 000 returned) and the black bet 1:1 (1 000 returned).
		assertEquals(37_000, res.get("payout").getAsLong());
		assertEquals(Money.ofWhole(1000) - 1_500 + 37_000, res.get("balance").getAsLong());
	}

	// ---------------------------------------------------------------- slots

	@Test
	void slotsRtpIsBelowOneAndReasonable() {
		SlotsGame.Paytable p = SlotsGame.paytable(cfg.casino.slots);
		BigDecimal rtp = SlotsGame.exactRtp(p);
		assertTrue(rtp.compareTo(new BigDecimal("0.90")) > 0 && rtp.compareTo(BigDecimal.ONE) < 0, "RTP " + rtp);
	}

	@Test
	void slotsLineEvaluation() {
		SlotsGame.Paytable p = SlotsGame.paytable(cfg.casino.slots);
		int star = p.symbols().indexOf("nether_star"), apple = p.symbols().indexOf("apple"), gold = p.symbols().indexOf("gold_ingot");
		int[][] g = {{gold, gold, apple}, {star, star, star}, {apple, apple, gold}};
		assertEquals(100_000, SlotsGame.lineWin(p, g, 0), "middle row three stars = 1000x");
		assertEquals(0, SlotsGame.lineWin(p, g, 1));
		assertEquals(300, SlotsGame.lineWin(p, g, 2), "two apples from the left = 3x");
		assertEquals(20_000, SlotsGame.payout(100, 100_000), "line stakes bet/5");
	}

	// ---------------------------------------------------------------- blackjack

	@Test
	void blackjackHandValues() {
		assertEquals(21, BlackjackGame.total(List.of(0, 12)), "A + K");
		assertTrue(BlackjackGame.isNatural(List.of(0, 9)));
		assertEquals(12, BlackjackGame.total(List.of(0, 0)), "two aces = 12");
		assertEquals(21, BlackjackGame.total(List.of(0, 0, 8)), "A A 9");
		assertEquals(22, BlackjackGame.total(List.of(9, 10, 1)), "bust");
		assertTrue(BlackjackGame.isSoft(List.of(0, 5)));
		assertTrue(BlackjackGame.dealerShouldHit(List.of(0, 5), true));
		assertFalse(BlackjackGame.dealerShouldHit(List.of(0, 5), false));
	}

	@Test
	void blackjackPayouts() {
		MineSinoConfig.Blackjack b = cfg.casino.blackjack;
		assertEquals(250, BlackjackGame.payoutFor(BlackjackGame.Result.BLACKJACK, 100, b), "3:2");
		assertEquals(200, BlackjackGame.payoutFor(BlackjackGame.Result.WIN, 100, b));
		assertEquals(100, BlackjackGame.payoutFor(BlackjackGame.Result.PUSH, 100, b));
		assertEquals(0, BlackjackGame.payoutFor(BlackjackGame.Result.BUST, 100, b));
		assertEquals(BlackjackGame.Result.PUSH, BlackjackGame.compare(List.of(0, 12), List.of(0, 11)));
		assertEquals(BlackjackGame.Result.DEALER_BUST, BlackjackGame.compare(List.of(9, 8), List.of(9, 5, 7)));
	}

	@Test
	void blackjackPaysExactlyOnceAndClosingTabletSettles() throws GameException {
		EconomyManager e = eco();
		// Fixed shoe order is irrelevant here; any deal works with the default secure RNG.
		CasinoService c = TestSupport.casino(e, cfg, dev.minesino.casino.GameRandom.secure());
		long before = e.balance(bob);
		JsonObject start = c.handle(bob, "blackjack", "start", TestSupport.json("{\"bet\":1000}"));
		if ("active".equals(start.get("status").getAsString())) {
			assertEquals(before - 1000, e.balance(bob), "stake is charged when the round starts");
			assertThrows(GameException.class, () -> c.handle(bob, "blackjack", "start", TestSupport.json("{\"bet\":1000}")));
			Map<String, JsonObject> settled = c.abandon(bob);
			assertTrue(settled.containsKey("blackjack"), "closing the tablet stands and settles");
			assertEquals("done", settled.get("blackjack").get("status").getAsString());
			assertThrows(GameException.class, () -> c.handle(bob, "blackjack", "stand", new JsonObject()));
		}
		long payouts = e.history(bob, TxType.Group.CASINO, 0, 100).entries().stream()
			.filter(t -> t.type() == TxType.PAYOUT && t.delta() > 0).count();
		assertTrue(payouts <= 1);
		assertTrue(c.abandon(bob).isEmpty(), "nothing left to settle");
	}

	// ---------------------------------------------------------------- plinko / dice / upgrade

	@Test
	void plinkoTablesHaveHouseEdge() {
		for (String risk : PlinkoGame.RISKS) {
			for (int rows : PlinkoGame.ROWS) {
				try {
					BigDecimal rtp = PlinkoGame.rtp(PlinkoGame.multipliers(cfg.casino.plinko, risk, rows));
					assertTrue(rtp.compareTo(BigDecimal.ONE) < 0 && rtp.compareTo(new BigDecimal("0.95")) > 0, risk + rows + " " + rtp);
				} catch (GameException ex) {
					fail(ex);
				}
			}
		}
	}

	@Test
	void plinkoPathDeterminesBin() throws GameException {
		EconomyManager e = eco();
		TestSupport.ScriptedRandom rnd = new TestSupport.ScriptedRandom(1, 1, 1, 1, 1, 1, 1, 1);
		CasinoService c = TestSupport.casino(e, cfg, rnd);
		JsonObject res = c.handle(bob, "plinko", "drop", TestSupport.json("{\"bet\":1000,\"risk\":\"low\",\"rows\":8}"));
		assertEquals(8, res.get("bin").getAsInt());
		assertEquals(5600, res.get("payout").getAsLong(), "5.6x edge bin");
		JsonArray path = res.getAsJsonArray("path");
		assertEquals(8, path.size());
	}

	@Test
	void diceMultiplierMatchesProbability() {
		long edge = MineSinoConfig.bps(cfg.casino.dice.houseEdgePercent);
		assertEquals(588, DiceGame.payout(100, 1, edge), "1 face: 6x minus 2% edge");
		assertEquals(196, DiceGame.payout(100, 3, edge), "3 faces: 2x minus 2%");
		for (int k = 1; k <= 5; k++) {
			// Expected return = k/6 * payout = (1 - edge)
			long expectedTimes6 = (long) k * DiceGame.payout(1_000_000, k, edge);
			assertTrue(Math.abs(expectedTimes6 - 6 * 980_000) <= 6 * k, "k=" + k);
		}
	}

	@Test
	void diceRejectsInvalidFaces() {
		CasinoService c = TestSupport.casino(eco(), cfg, new TestSupport.ScriptedRandom());
		assertThrows(GameException.class, () -> c.handle(bob, "dice", "roll", TestSupport.json("{\"bet\":100,\"faces\":[1,2,3,4,5,6]}")));
		assertThrows(GameException.class, () -> c.handle(bob, "dice", "roll", TestSupport.json("{\"bet\":100,\"faces\":[7]}")));
		assertThrows(GameException.class, () -> c.handle(bob, "dice", "roll", TestSupport.json("{\"bet\":100,\"faces\":[2,2]}")));
	}

	@Test
	void upgradeChanceFormula() {
		long commission = MineSinoConfig.bps(cfg.casino.upgrade.commissionPercent);
		assertEquals(475_000, UpgradeGame.chancePpm(200, commission), "2x at 5% commission = 47.5%");
		assertEquals(95_000, UpgradeGame.chancePpm(1000, commission), "10x = 9.5%");
		assertEquals(250, UpgradeGame.reward(100, 250));
	}

	@Test
	void upgradeRejectsOutOfRangeMultiplier() {
		CasinoService c = TestSupport.casino(eco(), cfg, new TestSupport.ScriptedRandom());
		assertThrows(GameException.class, () -> c.handle(bob, "upgrade", "upgrade", TestSupport.json("{\"bet\":100,\"mult\":100}")));
		assertThrows(GameException.class, () -> c.handle(bob, "upgrade", "upgrade", TestSupport.json("{\"bet\":100,\"mult\":99999999}")));
	}

	// ---------------------------------------------------------------- shared guarantees

	@Test
	void betLimitsAndBalanceAreChecked() {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom());
		GameException small = assertThrows(GameException.class, () -> c.handle(bob, "dice", "roll", TestSupport.json("{\"bet\":50,\"faces\":[1]}")));
		assertEquals("minesino.error.bet_too_small", small.key());
		GameException broke = assertThrows(GameException.class, () -> c.handle(bob, "dice", "roll", TestSupport.json("{\"bet\":200000,\"faces\":[1]}")));
		assertEquals("minesino.error.insufficient_funds", broke.key());
		assertEquals(Money.ofWhole(1000), e.balance(bob), "rejected rounds charge nothing");
	}

	@Test
	void payoutCannotBeAppliedTwice() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom());
		CasinoService.Round r = c.open(bob, "dice", 1000);
		assertEquals(5880, c.settle(r, 5880, null));
		long after = e.balance(bob);
		assertEquals(0, c.settle(r, 5880, null), "replayed settlement pays nothing");
		assertEquals(after, e.balance(bob));
	}

	@Test
	void unfinishedRoundsAreRefundedOnceAfterCrash() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom());
		CasinoService.Round r = c.open(bob, "blackjack", 2000);
		c.markPending(r);
		JsonArray saved = c.pendingToJson();
		JsonObject economySnapshot = e.toJson();
		// "Restart": fresh objects from the saved state.
		EconomyManager e2 = new EconomyManager(0, 200);
		e2.loadJson(economySnapshot);
		CasinoService c2 = TestSupport.casino(e2, cfg, new TestSupport.ScriptedRandom());
		assertEquals(1, c2.recoverPending(saved));
		assertEquals(Money.ofWhole(1000), e2.balance(bob));
		assertEquals(0, c2.recoverPending(saved), "second recovery does nothing");
		assertEquals(Money.ofWhole(1000), e2.balance(bob));
	}

	@Test
	void disabledGameIsUnavailable() {
		MineSinoConfig c2 = TestSupport.config();
		c2.casino.enabledGames.put("slots", false);
		CasinoService c = TestSupport.casino(eco(), c2, new TestSupport.ScriptedRandom());
		assertThrows(GameException.class, () -> c.handle(bob, "slots", "spin", TestSupport.json("{\"bet\":100}")));
		assertFalse(c.describe().getAsJsonObject("games").has("slots"));
		assertThrows(GameException.class, () -> c.handle(bob, "coinflip", "play", new JsonObject()), "unregistered games are unavailable");
	}

	// ---------------------------------------------------------------- extra games

	@Test
	void coinflipPaysTwiceMinusEdge() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(0));
		JsonObject r = c.handle(bob, "coinflip", "flip", TestSupport.json("{\"bet\":1000,\"side\":\"heads\"}"));
		assertTrue(r.get("win").getAsBoolean());
		assertEquals(1960, r.get("payout").getAsLong());
	}

	@Test
	void crashProbabilityMatchesFormula() {
		long edge = 300;
		// P(C >= T) = (1-e)/T: count the draws that reach 2x over an evenly spaced grid of U.
		int n = 100_000, hits = 0;
		for (int i = 0; i < n; i++) {
			long r = (long) i * dev.minesino.casino.games.CrashGame.MAX_CRASH * 100 / n;
			if (dev.minesino.casino.games.CrashGame.crashPoint(r, edge) >= 200) hits++;
		}
		assertEquals(0.485, hits / (double) n, 0.002);
		assertEquals(100, dev.minesino.casino.games.CrashGame.crashPoint(0, edge), "never below 1.00x");
	}

	@Test
	void minesExpectedReturnEqualsOneMinusEdge() {
		long edge = 200;
		for (int m = 1; m <= 24; m++) {
			for (int k = 1; k <= 25 - m; k++) {
				// survival probability prod (25-m-i)/(25-i); expected value = P * payout
				double p = 1;
				for (int i = 0; i < k; i++) p *= (25.0 - m - i) / (25.0 - i);
				double ev = p * dev.minesino.casino.games.MinesGame.payout(1_000_000_000L, m, k, edge) / 1_000_000_000.0;
				assertEquals(0.98, ev, 0.0001, "m=" + m + " k=" + k);
			}
		}
	}

	@Test
	void minesRoundAndAbandonCashout() throws GameException {
		EconomyManager e = eco();
		// mines placed on tiles 0..2, then reveal tile 10 safely
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(0, 1, 2));
		c.handle(bob, "mines", "start", TestSupport.json("{\"bet\":1000,\"mines\":3}"));
		JsonObject r = c.handle(bob, "mines", "reveal", TestSupport.json("{\"tile\":10}"));
		assertFalse(r.has("minePositions"), "mines stay hidden during the round");
		Map<String, JsonObject> settled = c.abandon(bob);
		long expected = dev.minesino.casino.games.MinesGame.payout(1000, 3, 1, 200);
		assertEquals(expected, settled.get("mines").get("payout").getAsLong());
		assertEquals(Money.ofWhole(1000) - 1000 + expected, e.balance(bob));
		assertThrows(GameException.class, () -> c.handle(bob, "mines", "reveal", TestSupport.json("{\"tile\":11}")));
	}

	@Test
	void minesHittingAMineLoses() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(5));
		c.handle(bob, "mines", "start", TestSupport.json("{\"bet\":1000,\"mines\":1}"));
		JsonObject r = c.handle(bob, "mines", "reveal", TestSupport.json("{\"tile\":5}"));
		assertEquals("boom", r.get("result").getAsString());
		assertEquals(0, r.get("payout").getAsLong());
		assertEquals(Money.ofWhole(1000) - 1000, e.balance(bob));
	}

	@Test
	void wheelReturnsNinetySixPercent() {
		long sum = 0;
		for (long m : dev.minesino.casino.games.WheelGame.SEGMENTS) sum += m;
		assertEquals(30, dev.minesino.casino.games.WheelGame.SEGMENTS.length);
		assertEquals(2880, sum, "average multiplier 0.96");
	}

	@Test
	void kenoRowsHaveHouseEdge() {
		for (int k = 1; k <= 10; k++) {
			BigDecimal ev = BigDecimal.ZERO;
			java.math.BigInteger total = binom(40, 10);
			for (int h = 0; h <= k; h++) {
				java.math.BigInteger ways = binom(k, h).multiply(binom(40 - k, 10 - h));
				ev = ev.add(new BigDecimal(ways.multiply(java.math.BigInteger.valueOf(dev.minesino.casino.games.KenoGame.PAYTABLE[k - 1][h]))));
			}
			BigDecimal rtp = ev.divide(new BigDecimal(total).multiply(BigDecimal.valueOf(100)), 6, java.math.RoundingMode.HALF_UP);
			assertTrue(rtp.compareTo(new BigDecimal("0.94")) > 0 && rtp.compareTo(new BigDecimal("0.96")) < 0, "picks " + k + " rtp " + rtp);
		}
	}

	private static java.math.BigInteger binom(int n, int k) {
		java.math.BigInteger r = java.math.BigInteger.ONE;
		for (int i = 1; i <= k; i++) r = r.multiply(java.math.BigInteger.valueOf(n - k + i)).divide(java.math.BigInteger.valueOf(i));
		return r;
	}

	@Test
	void hiloStepIsFairMinusEdge() throws GameException {
		EconomyManager e = eco();
		// first card 7 (index 6), next card 10 (index 9): "higher" wins with 7/13 chance
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(6, 9));
		c.handle(bob, "hilo", "start", TestSupport.json("{\"bet\":1300}"));
		JsonObject r = c.handle(bob, "hilo", "higher", new JsonObject());
		assertEquals(1, r.get("steps").getAsInt());
		// 1300 * 13 * 0.97 / 7 = 2341.85 -> 2341
		assertEquals(2341, r.get("cashout").getAsLong());
		JsonObject out = c.handle(bob, "hilo", "cashout", new JsonObject());
		assertEquals(2341, out.get("payout").getAsLong());
		assertEquals(Money.ofWhole(1000) - 1300 + 2341, e.balance(bob));
	}

	@Test
	void hiloRejectsCertainGuess() throws GameException {
		CasinoService c = TestSupport.casino(eco(), cfg, new TestSupport.ScriptedRandom(0));
		c.handle(bob, "hilo", "start", TestSupport.json("{\"bet\":1000}"));
		assertThrows(GameException.class, () -> c.handle(bob, "hilo", "higher", new JsonObject()), "ace: 'higher or same' always wins");
		assertEquals(1000, c.abandon(bob).get("hilo").get("payout").getAsLong(), "closing with no guesses returns the stake");
	}

	@Test
	void crashMultiplierCurve() {
		assertEquals(100, dev.minesino.casino.games.CrashGame.multiplierAt(0));
		assertEquals(101, dev.minesino.casino.games.CrashGame.multiplierAt(100), "+1.5% per 100 ms");
		long two = dev.minesino.casino.games.CrashGame.multiplierAt(4700);
		assertTrue(two >= 198 && two <= 202, "about x2 after 4.7 s: " + two);
	}

	@Test
	void crashCashOutAnyTimeBeforeExplosion() throws GameException {
		EconomyManager e = eco();
		// r chosen so the crash point is about 10x
		long r = 90_300_000L;
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(r));
		JsonObject launch = c.handle(bob, "crash", "launch", TestSupport.json("{\"bet\":1000}"));
		assertEquals("flying", launch.get("status").getAsString());
		assertFalse(launch.has("crash"), "crash point is secret while flying");
		assertEquals(Money.ofWhole(1000) - 1000, e.balance(bob));
		TestSupport.CLOCK.addAndGet(4700);
		JsonObject out = c.handle(bob, "crash", "cashout", new JsonObject());
		long m = out.get("cashedAt").getAsLong();
		assertTrue(out.get("win").getAsBoolean());
		assertEquals(1000 * m / 100, out.get("payout").getAsLong(), "payout = bet x server multiplier");
		assertThrows(GameException.class, () -> c.handle(bob, "crash", "cashout", new JsonObject()), "cannot cash out twice");
	}

	@Test
	void crashExplodesOnTickAndLateCashOutLoses() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(50_000_000L)); // ~1.94x
		c.handle(bob, "crash", "launch", TestSupport.json("{\"bet\":1000}"));
		TestSupport.CLOCK.addAndGet(60_000);
		java.util.List<JsonObject> events = new java.util.ArrayList<>();
		c.tick((p, res) -> events.add(res));
		assertEquals(1, events.size(), "server ends the round by itself");
		assertFalse(events.get(0).get("win").getAsBoolean());
		assertEquals(0, events.get(0).get("payout").getAsLong());
		assertThrows(GameException.class, () -> c.handle(bob, "crash", "cashout", new JsonObject()));
	}

	@Test
	void crashAutoCashOut() throws GameException {
		EconomyManager e = eco();
		CasinoService c = TestSupport.casino(e, cfg, new TestSupport.ScriptedRandom(90_300_000L)); // ~10x
		c.handle(bob, "crash", "launch", TestSupport.json("{\"bet\":1000,\"auto\":200}"));
		TestSupport.CLOCK.addAndGet(20_000);
		java.util.List<JsonObject> events = new java.util.ArrayList<>();
		c.tick((p, res) -> events.add(res));
		assertEquals(2000, events.get(0).get("payout").getAsLong(), "auto cash-out pays exactly the target");
	}
}
