package dev.minesino.storage;

import dev.minesino.economy.Transaction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * Append-only daily journal of every money movement plus admin and security events.
 * Lines are buffered and written off-thread in batches.
 */
public final class Journal {
	private final Path dir;
	private final AsyncWriter writer;
	private final List<String> buffer = new ArrayList<>();
	private volatile boolean enabled = true;
	private volatile boolean logSecurity = true;

	public Journal(Path dir, AsyncWriter writer) {
		this.dir = dir;
		this.writer = writer;
	}

	public void configure(boolean enabled, boolean logSecurity) {
		this.enabled = enabled;
		this.logSecurity = logSecurity;
	}

	public void record(Transaction t) {
		if (!enabled) return;
		line(String.format("%s TX #%d %s %s delta=%d balance=%d detail=%s outcome=%s op=%s",
			Instant.ofEpochMilli(t.time()), t.id(), t.player(), t.type(), t.delta(), t.balanceAfter(),
			t.detail(), t.outcome(), t.opId()));
	}

	/** Suspicious or rejected actions (replays, forged packets, invalid params). */
	public void security(String player, String message) {
		if (!enabled || !logSecurity) return;
		line(Instant.now() + " SECURITY " + player + " " + message);
	}

	public void admin(String actor, String message) {
		if (!enabled) return;
		line(Instant.now() + " ADMIN " + actor + " " + message);
	}

	private synchronized void line(String s) {
		buffer.add(s.replace('\n', ' '));
		if (buffer.size() >= 256) flush();
	}

	public synchronized void flush() {
		if (buffer.isEmpty()) return;
		List<String> lines = new ArrayList<>(buffer);
		buffer.clear();
		Path file = dir.resolve("journal-" + LocalDate.now() + ".log");
		writer.submit("journal", () -> {
			Files.createDirectories(dir);
			Files.write(file, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
		});
	}
}
