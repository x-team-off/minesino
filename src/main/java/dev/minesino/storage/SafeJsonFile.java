package dev.minesino.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Crash-safe JSON file: data is written to a temp file, fsynced and atomically moved over the
 * target, so the file on disk is always either the old or the new complete version.
 * The previous version is rotated into numbered backups. Loading falls back to backups when the
 * main file is unreadable, and a corrupt file is preserved before anything overwrites it.
 */
public final class SafeJsonFile {
	private static final Logger LOG = LoggerFactory.getLogger("MineSino/Storage");
	private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

	private final Path file;
	private final int backups;

	public SafeJsonFile(Path file, int backups) {
		this.file = file;
		this.backups = Math.max(0, backups);
	}

	public Path path() { return file; }

	private Path backup(int i) {
		return file.resolveSibling(file.getFileName() + ".bak" + i);
	}

	public synchronized void write(String json) throws IOException {
		Files.createDirectories(file.getParent());
		Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
		try (FileChannel ch = FileChannel.open(tmp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
			 OutputStream out = Channels.newOutputStream(ch)) {
			out.write(json.getBytes(StandardCharsets.UTF_8));
			out.flush();
			ch.force(true);
		}
		if (Files.exists(file) && backups > 0) {
			for (int i = backups - 1; i >= 1; i--) {
				if (Files.exists(backup(i))) Files.move(backup(i), backup(i + 1), StandardCopyOption.REPLACE_EXISTING);
			}
			Files.copy(file, backup(1), StandardCopyOption.REPLACE_EXISTING);
		}
		try {
			Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
		} catch (AtomicMoveNotSupportedException e) {
			Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
		}
	}

	public void write(JsonElement json) throws IOException {
		write(GSON.toJson(json));
	}

	public static String serialize(JsonElement json) {
		return GSON.toJson(json);
	}

	/** Result of a load: the parsed object (or null when nothing usable exists) and where it came from. */
	public record Loaded(JsonObject data, String source, boolean recovered) {}

	public synchronized Loaded load() {
		if (!Files.exists(file) && !Files.exists(backup(1))) return new Loaded(null, "none", false);
		JsonObject main = tryRead(file);
		if (main != null) return new Loaded(main, file.getFileName().toString(), false);
		if (Files.exists(file)) quarantine(file);
		for (int i = 1; i <= backups; i++) {
			JsonObject b = tryRead(backup(i));
			if (b != null) {
				LOG.error("{} was unreadable, recovered state from backup {}", file.getFileName(), backup(i).getFileName());
				return new Loaded(b, backup(i).getFileName().toString(), true);
			}
		}
		LOG.error("{} and all backups are unreadable; starting with empty state", file.getFileName());
		return new Loaded(null, "none", true);
	}

	private static JsonObject tryRead(Path p) {
		if (!Files.exists(p)) return null;
		try {
			String s = Files.readString(p, StandardCharsets.UTF_8);
			JsonElement e = JsonParser.parseString(s);
			return e.isJsonObject() ? e.getAsJsonObject() : null;
		} catch (IOException | RuntimeException e) {
			LOG.error("Failed to read {}: {}", p, e.toString());
			return null;
		}
	}

	private static void quarantine(Path p) {
		try {
			String stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"));
			Files.copy(p, p.resolveSibling(p.getFileName() + ".corrupt-" + stamp), StandardCopyOption.REPLACE_EXISTING);
		} catch (IOException e) {
			LOG.error("Could not preserve corrupt file {}", p, e);
		}
	}
}
