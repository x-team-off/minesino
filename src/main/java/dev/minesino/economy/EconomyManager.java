package dev.minesino.economy;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.minesino.util.Money;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The single entry point for every balance change. Thread-safe: all mutations are serialized
 * on {@code this}, so concurrent requests can never drive a balance negative.
 *
 * <p>Every mutation carries an operation id. An id that was already applied is rejected with
 * {@link TxResult.Status#DUPLICATE}; this is what prevents double payouts and double charges when
 * a packet is replayed or a recovery runs twice. Applied ids are persisted together with balances.
 */
public final class EconomyManager {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Economy");
	private static final int APPLIED_OPS_LIMIT = 200_000;

	public interface Listener {
		void onBalanceChanged(UUID player, long newBalance);
	}

	private final Map<UUID, Account> accounts = new HashMap<>();
	/** Insertion ordered so the oldest ids are evicted first. */
	private final LinkedHashMap<String, Long> appliedOps = new LinkedHashMap<>();
	private final List<Listener> listeners = new CopyOnWriteArrayList<>();
	private volatile Consumer<Transaction> journal = t -> {};
	private long nextTxId = 1;
	private long startingBalance;
	private int historyLimit;
	private boolean dirty;
	private final java.util.function.LongSupplier clock;

	public EconomyManager(long startingBalance, int historyLimit) {
		this(startingBalance, historyLimit, System::currentTimeMillis);
	}

	public EconomyManager(long startingBalance, int historyLimit, java.util.function.LongSupplier clock) {
		this.startingBalance = startingBalance;
		this.historyLimit = Math.max(10, historyLimit);
		this.clock = clock;
	}

	public synchronized void reconfigure(long startingBalance, int historyLimit) {
		this.startingBalance = startingBalance;
		this.historyLimit = Math.max(10, historyLimit);
		for (Account a : accounts.values()) trimHistory(a);
	}

	public void addListener(Listener l) { listeners.add(l); }
	public void setJournal(Consumer<Transaction> journal) { this.journal = journal == null ? t -> {} : journal; }

	// ---------------------------------------------------------------- accounts

	public synchronized boolean hasAccount(UUID id) {
		return accounts.containsKey(id);
	}

	/** Creates the account with the configured starting balance on first sight. */
	public synchronized Account getOrCreate(UUID id, String name) {
		Account a = accounts.get(id);
		if (a == null) {
			a = new Account(id, name, 0);
			accounts.put(id, a);
			dirty = true;
			if (startingBalance > 0) {
				applyInternal(a, startingBalance, TxType.INITIAL, "starting_balance", "", "init:" + id);
			}
		} else if (name != null && !name.isEmpty() && !name.equals(a.lastKnownName)) {
			a.lastKnownName = name; // renames never touch the balance; it is keyed by UUID
			dirty = true;
		}
		return a;
	}

	public synchronized long balance(UUID id) {
		Account a = accounts.get(id);
		return a == null ? 0 : a.balance;
	}

	public synchronized UUID findByName(String name) {
		for (Account a : accounts.values()) {
			if (a.lastKnownName != null && a.lastKnownName.equalsIgnoreCase(name)) return a.id;
		}
		return null;
	}

	public synchronized PlayerStats stats(UUID id) {
		Account a = accounts.get(id);
		return a == null ? new PlayerStats() : a.stats.copy();
	}

	public synchronized void updateStats(UUID id, Consumer<PlayerStats> change) {
		Account a = accounts.get(id);
		if (a != null) {
			change.accept(a.stats);
			dirty = true;
		}
	}

	public synchronized Collection<UUID> accountIds() {
		return List.copyOf(accounts.keySet());
	}

	public synchronized boolean isApplied(String opId) {
		return appliedOps.containsKey(opId);
	}

	// ---------------------------------------------------------------- mutations

	public TxResult deposit(UUID id, long amount, TxType type, String detail, String opId) {
		return deposit(id, amount, type, detail, "", opId);
	}

	public synchronized TxResult deposit(UUID id, long amount, TxType type, String detail, String outcome, String opId) {
		if (amount <= 0) return TxResult.fail(TxResult.Status.INVALID_AMOUNT, balance(id));
		return change(id, amount, type, detail, outcome, opId);
	}

	public TxResult withdraw(UUID id, long amount, TxType type, String detail, String opId) {
		return withdraw(id, amount, type, detail, "", opId);
	}

	public synchronized TxResult withdraw(UUID id, long amount, TxType type, String detail, String outcome, String opId) {
		if (amount <= 0) return TxResult.fail(TxResult.Status.INVALID_AMOUNT, balance(id));
		return change(id, -amount, type, detail, outcome, opId);
	}

	/** Administrative absolute set; recorded as a delta so the ledger stays consistent. */
	public synchronized TxResult set(UUID id, long newBalance, String detail, String opId) {
		if (newBalance < 0 || newBalance > Money.MAX_AMOUNT) return TxResult.fail(TxResult.Status.INVALID_AMOUNT, balance(id));
		Account a = getOrCreate(id, null);
		long delta = newBalance - a.balance;
		if (appliedOps.containsKey(opId)) return TxResult.fail(TxResult.Status.DUPLICATE, a.balance);
		return applyInternal(a, delta, TxType.ADMIN_SET, detail, "", opId);
	}

	/**
	 * Records a zero-amount ledger entry (for example a lost round whose stake was already charged)
	 * so the history shows the round result. Does not change the balance.
	 */
	public synchronized void note(UUID id, TxType type, String detail, String outcome, String opId) {
		Account a = accounts.get(id);
		if (a == null || appliedOps.containsKey(opId)) return;
		applyInternal(a, 0, type, detail, outcome, opId);
	}

	private TxResult change(UUID id, long delta, TxType type, String detail, String outcome, String opId) {
		Account a = getOrCreate(id, null);
		if (opId == null || opId.isEmpty()) throw new IllegalArgumentException("opId required");
		if (appliedOps.containsKey(opId)) {
			LOG.warn("Rejected duplicate operation {} for {}", opId, id);
			return TxResult.fail(TxResult.Status.DUPLICATE, a.balance);
		}
		if (delta < 0 && a.balance + delta < 0) return TxResult.fail(TxResult.Status.INSUFFICIENT_FUNDS, a.balance);
		return applyInternal(a, delta, type, detail, outcome, opId);
	}

	private TxResult applyInternal(Account a, long delta, TxType type, String detail, String outcome, String opId) {
		long after;
		try {
			after = Money.add(a.balance, delta);
		} catch (ArithmeticException e) {
			return TxResult.fail(TxResult.Status.LIMIT_EXCEEDED, a.balance);
		}
		if (after < 0) return TxResult.fail(TxResult.Status.INSUFFICIENT_FUNDS, a.balance);
		a.balance = after;
		Transaction tx = new Transaction(nextTxId++, clock.getAsLong(), a.id, type, delta, after,
			detail == null ? "" : detail, outcome == null ? "" : outcome, opId);
		a.history.addFirst(tx);
		trimHistory(a);
		rememberOp(opId, tx.id());
		dirty = true;
		try {
			journal.accept(tx);
		} catch (RuntimeException e) {
			LOG.error("Journal write failed", e);
		}
		if (delta != 0) {
			for (Listener l : listeners) {
				try {
					l.onBalanceChanged(a.id, after);
				} catch (RuntimeException e) {
					LOG.error("Balance listener failed", e);
				}
			}
		}
		return new TxResult(TxResult.Status.OK, after, tx);
	}

	private void rememberOp(String opId, long txId) {
		appliedOps.put(opId, txId);
		if (appliedOps.size() > APPLIED_OPS_LIMIT) {
			Iterator<String> it = appliedOps.keySet().iterator();
			for (int i = 0; i < APPLIED_OPS_LIMIT / 10 && it.hasNext(); i++) {
				it.next();
				it.remove();
			}
		}
	}

	private void trimHistory(Account a) {
		while (a.history.size() > historyLimit) a.history.removeLast();
	}

	// ---------------------------------------------------------------- history

	public record HistoryPage(List<Transaction> entries, int page, int totalPages, int totalEntries) {}

	public synchronized HistoryPage history(UUID id, TxType.Group group, int page, int pageSize) {
		Account a = accounts.get(id);
		if (a == null) return new HistoryPage(List.of(), 0, 1, 0);
		List<Transaction> filtered = new ArrayList<>();
		for (Transaction t : a.history) {
			if (group == TxType.Group.ALL || t.type().group() == group) filtered.add(t);
		}
		pageSize = Math.clamp(pageSize, 1, 100);
		int total = Math.max(1, (filtered.size() + pageSize - 1) / pageSize);
		page = Math.clamp(page, 0, total - 1);
		int from = page * pageSize;
		int to = Math.min(filtered.size(), from + pageSize);
		return new HistoryPage(List.copyOf(filtered.subList(from, to)), page, total, filtered.size());
	}

	// ---------------------------------------------------------------- persistence

	public synchronized boolean isDirty() { return dirty; }
	public synchronized void clearDirty() { dirty = false; }

	public synchronized JsonObject toJson() {
		JsonObject root = new JsonObject();
		root.addProperty("nextTxId", nextTxId);
		JsonObject accs = new JsonObject();
		for (Account a : accounts.values()) {
			JsonObject o = new JsonObject();
			o.addProperty("name", a.lastKnownName == null ? "" : a.lastKnownName);
			o.addProperty("balance", a.balance);
			o.add("stats", Json.statsToJson(a.stats));
			JsonArray h = new JsonArray();
			for (Transaction t : a.history) h.add(Json.txToJson(t));
			o.add("history", h);
			accs.add(a.id.toString(), o);
		}
		root.add("accounts", accs);
		JsonObject ops = new JsonObject();
		for (Map.Entry<String, Long> e : appliedOps.entrySet()) ops.addProperty(e.getKey(), e.getValue());
		root.add("appliedOps", ops);
		return root;
	}

	/**
	 * Loads state, validating every value. Corrupt entries are skipped (and reported) instead of
	 * aborting the whole load. Returns the number of problems found.
	 */
	public synchronized int loadJson(JsonObject root) {
		int problems = 0;
		accounts.clear();
		appliedOps.clear();
		nextTxId = Math.max(1, Json.getLong(root, "nextTxId", 1));
		JsonObject accs = root.has("accounts") && root.get("accounts").isJsonObject() ? root.getAsJsonObject("accounts") : new JsonObject();
		long maxTx = 0;
		for (Map.Entry<String, JsonElement> e : accs.entrySet()) {
			try {
				UUID id = UUID.fromString(e.getKey());
				JsonObject o = e.getValue().getAsJsonObject();
				long bal = Json.getLong(o, "balance", 0);
				if (bal < 0 || bal > Money.MAX_AMOUNT) {
					LOG.error("Account {} has invalid balance {}, clamping", id, bal);
					bal = Math.clamp(bal, 0, Money.MAX_AMOUNT);
					problems++;
				}
				Account a = new Account(id, Json.getString(o, "name", ""), bal);
				if (o.has("stats")) Json.statsFromJson(o.getAsJsonObject("stats"), a.stats);
				if (o.has("history")) {
					for (JsonElement h : o.getAsJsonArray("history")) {
						Transaction t = Json.txFromJson(h.getAsJsonObject(), id);
						if (t != null) {
							a.history.addLast(t);
							maxTx = Math.max(maxTx, t.id());
						} else problems++;
					}
				}
				trimHistory(a);
				accounts.put(id, a);
			} catch (RuntimeException ex) {
				LOG.error("Skipping corrupt account entry {}", e.getKey(), ex);
				problems++;
			}
		}
		if (root.has("appliedOps") && root.get("appliedOps").isJsonObject()) {
			for (Map.Entry<String, JsonElement> e : root.getAsJsonObject("appliedOps").entrySet()) {
				try {
					appliedOps.put(e.getKey(), e.getValue().getAsLong());
				} catch (RuntimeException ex) {
					problems++;
				}
			}
		}
		// Ids from history are applied too, even if the appliedOps section was lost.
		for (Account a : accounts.values()) {
			for (Transaction t : a.history) appliedOps.putIfAbsent(t.opId(), t.id());
		}
		nextTxId = Math.max(nextTxId, maxTx + 1);
		dirty = false;
		return problems;
	}

	/** Total money in circulation; used by /minesino info. */
	public synchronized long totalSupply() {
		long s = 0;
		for (Account a : accounts.values()) s += a.balance;
		return s;
	}

	public synchronized int accountCount() {
		return accounts.size();
	}

	public synchronized String nameOf(UUID id) {
		Account a = accounts.get(id);
		return a == null ? null : a.lastKnownName;
	}
}
