package dev.minesino.storage;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Single background thread for disk I/O so the server tick never blocks on file writes.
 * Tasks run strictly in submission order.
 */
public final class AsyncWriter implements AutoCloseable {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/IO");
	private final ExecutorService executor = Executors.newSingleThreadExecutor(r -> {
		Thread t = new Thread(r, "MineSino-IO");
		t.setDaemon(true);
		return t;
	});

	public void submit(String what, IoTask task) {
		executor.execute(() -> {
			try {
				task.run();
			} catch (Exception e) {
				LOG.error("Background I/O task '{}' failed", what, e);
			}
		});
	}

	/** Waits for all queued writes; used on server shutdown. */
	public void flush(long timeoutMs) {
		CountDownLatch latch = new CountDownLatch(1);
		executor.execute(latch::countDown);
		try {
			if (!latch.await(timeoutMs, TimeUnit.MILLISECONDS)) LOG.warn("Timed out waiting for pending writes");
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
	}

	@Override
	public void close() {
		flush(10_000);
		executor.shutdown();
	}

	@FunctionalInterface
	public interface IoTask {
		void run() throws Exception;
	}
}
