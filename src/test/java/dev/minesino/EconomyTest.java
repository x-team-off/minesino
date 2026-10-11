package dev.minesino;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.minesino.economy.EconomyManager;
import dev.minesino.economy.TxResult;
import dev.minesino.economy.TxType;
import dev.minesino.storage.SafeJsonFile;
import dev.minesino.util.Money;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EconomyTest {
	private final UUID alice = UUID.fromString("00000000-0000-0000-0000-00000000000a");

	@Test
	void depositAndWithdraw() {
		EconomyManager eco = new EconomyManager(Money.ofWhole(1000), 100);
		eco.getOrCreate(alice, "Alice");
		assertEquals(100_000, eco.balance(alice));
		assertTrue(eco.deposit(alice, 2_550, TxType.ADMIN_ADD, "t", "op1").ok());
		assertEquals(102_550, eco.balance(alice));
		assertTrue(eco.withdraw(alice, 50, TxType.BET, "t", "op2").ok());
		assertEquals(102_500, eco.balance(alice));
	}

	@Test
	void neverNegative() {
		EconomyManager eco = new EconomyManager(Money.ofWhole(10), 100);
		eco.getOrCreate(alice, "Alice");
		TxResult r = eco.withdraw(alice, Money.ofWhole(11), TxType.BET, "t", "op");
		assertEquals(TxResult.Status.INSUFFICIENT_FUNDS, r.status());
		assertEquals(Money.ofWhole(10), eco.balance(alice));
		assertEquals(TxResult.Status.INVALID_AMOUNT, eco.withdraw(alice, -5, TxType.BET, "t", "neg").status());
		assertEquals(TxResult.Status.INVALID_AMOUNT, eco.deposit(alice, 0, TxType.ADMIN_ADD, "t", "zero").status());
	}

	@Test
	void concurrentWithdrawalsCannotOverdraw() throws Exception {
		EconomyManager eco = new EconomyManager(Money.ofWhole(100), 1000);
		eco.getOrCreate(alice, "Alice");
		int threads = 16;
		CountDownLatch start = new CountDownLatch(1);
		AtomicInteger ok = new AtomicInteger();
		List<Thread> ts = new ArrayList<>();
		for (int i = 0; i < threads; i++) {
			int n = i;
			Thread t = new Thread(() -> {
				try {
					start.await();
				} catch (InterruptedException ignored) {}
				for (int k = 0; k < 50; k++) {
					if (eco.withdraw(alice, Money.ofWhole(1), TxType.BET, "t", "w" + n + "-" + k).ok()) ok.incrementAndGet();
				}
			});
			ts.add(t);
			t.start();
		}
		start.countDown();
		for (Thread t : ts) t.join();
		assertEquals(100, ok.get());
		assertEquals(0, eco.balance(alice));
	}

	@Test
	void overflowIsRejected() {
		EconomyManager eco = new EconomyManager(0, 100);
		eco.getOrCreate(alice, "Alice");
		assertTrue(eco.deposit(alice, Money.MAX_AMOUNT, TxType.ADMIN_ADD, "t", "a").ok());
		TxResult r = eco.deposit(alice, 1, TxType.ADMIN_ADD, "t", "b");
		assertEquals(TxResult.Status.LIMIT_EXCEEDED, r.status());
		assertEquals(Money.MAX_AMOUNT, eco.balance(alice));
		assertThrows(ArithmeticException.class, () -> Money.add(Long.MAX_VALUE, 1));
	}

	@Test
	void sameOperationIsAppliedOnce() {
		EconomyManager eco = new EconomyManager(0, 100);
		eco.getOrCreate(alice, "Alice");
		assertTrue(eco.deposit(alice, 500, TxType.PAYOUT, "slots", "round42:pay").ok());
		TxResult again = eco.deposit(alice, 500, TxType.PAYOUT, "slots", "round42:pay");
		assertEquals(TxResult.Status.DUPLICATE, again.status());
		assertEquals(500, eco.balance(alice));
	}

	@Test
	void appliedOperationsSurviveRestart() {
		EconomyManager eco = new EconomyManager(0, 100);
		eco.getOrCreate(alice, "Alice");
		eco.deposit(alice, 700, TxType.PAYOUT, "x", "r1:pay");
		EconomyManager reloaded = new EconomyManager(0, 100);
		assertEquals(0, reloaded.loadJson(eco.toJson()));
		assertEquals(700, reloaded.balance(alice));
		assertEquals(TxResult.Status.DUPLICATE, reloaded.deposit(alice, 700, TxType.PAYOUT, "x", "r1:pay").status());
	}

	@Test
	void saveAndLoadBalance(@TempDir Path dir) throws Exception {
		EconomyManager eco = new EconomyManager(Money.ofWhole(1000), 100);
		eco.getOrCreate(alice, "Alice");
		eco.withdraw(alice, 12_345, TxType.BET, "roulette", "op");
		SafeJsonFile file = new SafeJsonFile(dir.resolve("economy.json"), 3);
		file.write(eco.toJson());
		EconomyManager loaded = new EconomyManager(Money.ofWhole(1000), 100);
		loaded.loadJson(file.load().data());
		assertEquals(eco.balance(alice), loaded.balance(alice));
		assertEquals(2, loaded.history(alice, TxType.Group.ALL, 0, 10).totalEntries());
		// Re-creating the account (rejoin / rename) must not reset the balance.
		loaded.getOrCreate(alice, "AliceRenamed");
		assertEquals(eco.balance(alice), loaded.balance(alice));
		assertEquals("AliceRenamed", loaded.nameOf(alice));
	}

	@Test
	void corruptFileRecoversFromBackup(@TempDir Path dir) throws Exception {
		Path p = dir.resolve("economy.json");
		SafeJsonFile file = new SafeJsonFile(p, 3);
		EconomyManager eco = new EconomyManager(0, 100);
		eco.getOrCreate(alice, "Alice");
		eco.deposit(alice, 999, TxType.ADMIN_ADD, "t", "a");
		file.write(eco.toJson());
		eco.deposit(alice, 1, TxType.ADMIN_ADD, "t", "b");
		file.write(eco.toJson()); // previous version rotated into .bak1
		Files.writeString(p, "{ broken json", StandardCharsets.UTF_8);
		SafeJsonFile.Loaded l = file.load();
		assertTrue(l.recovered());
		EconomyManager loaded = new EconomyManager(0, 100);
		loaded.loadJson(l.data());
		assertEquals(999, loaded.balance(alice));
		try (var s = Files.list(dir)) {
			assertTrue(s.anyMatch(f -> f.getFileName().toString().contains(".corrupt-")), "corrupt file must be preserved");
		}
	}

	@Test
	void invalidStoredBalanceIsClamped() {
		JsonObject root = TestSupport.json("{\"accounts\":{\"" + alice + "\":{\"name\":\"A\",\"balance\":-50}}}");
		EconomyManager eco = new EconomyManager(0, 100);
		assertEquals(1, eco.loadJson(root));
		assertEquals(0, eco.balance(alice));
	}

	@Test
	void historyPagingAndFilter() {
		EconomyManager eco = new EconomyManager(0, 100);
		eco.getOrCreate(alice, "A");
		for (int i = 0; i < 25; i++) eco.deposit(alice, 10, TxType.ITEM_SALE, "x", "s" + i);
		for (int i = 0; i < 5; i++) eco.withdraw(alice, 1, TxType.BET, "x", "b" + i);
		EconomyManager.HistoryPage p = eco.history(alice, TxType.Group.SALES, 2, 10);
		assertEquals(3, p.totalPages());
		assertEquals(5, p.entries().size());
		assertEquals(5, eco.history(alice, TxType.Group.CASINO, 0, 10).totalEntries());
	}

	@Test
	void historyIsBounded() {
		EconomyManager eco = new EconomyManager(0, 20);
		eco.getOrCreate(alice, "A");
		for (int i = 0; i < 100; i++) eco.deposit(alice, 1, TxType.ITEM_SALE, "x", "s" + i);
		assertEquals(20, eco.history(alice, TxType.Group.ALL, 0, 100).totalEntries());
	}

	@Test
	void moneyFormattingAndParsing() {
		assertEquals("1 250 MS", Money.format(125_000, 0, "MS"));
		assertEquals("1 250.5 MS", Money.format(125_050, 2, "MS"));
		assertEquals("0.1 MS", Money.format(10, 2, "MS"));
		assertEquals("1 000 MS", Money.format(100_000, 2, "MS"));
		assertEquals("+200 MS", Money.formatSigned(20_000, 0, "MS"));
		assertEquals(125_050, Money.parse("1 250,5"));
		assertEquals(-1, Money.parse("1.234"));
		assertEquals(-1, Money.parse("-5"));
		assertEquals(-1, Money.parse("abc"));
	}
}
