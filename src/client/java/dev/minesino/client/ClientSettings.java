package dev.minesino.client;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.loader.api.FabricLoader;

/** Purely visual client preferences ({@code config/minesino-client.json}). */
public final class ClientSettings {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("minesino-client.json");
	private static ClientSettings instance;

	public boolean animations = true;
	/** Fewer glow layers and shadows for weak machines. */
	public boolean reducedEffects = false;
	public boolean sounds = true;
	public boolean showHudBalance = true;
	public boolean compactHudNumbers = true;
	public boolean showToasts = true;
	/** Speed factor for game animations (0.5 .. 2). */
	public float animationSpeed = 1.0f;

	public static ClientSettings get() {
		if (instance == null) load();
		return instance;
	}

	public static void load() {
		try {
			if (Files.exists(FILE)) {
				instance = GSON.fromJson(Files.readString(FILE, StandardCharsets.UTF_8), ClientSettings.class);
			}
		} catch (Exception ignored) {
			// fall back to defaults
		}
		if (instance == null) instance = new ClientSettings();
		instance.animationSpeed = Math.clamp(instance.animationSpeed, 0.5f, 2f);
	}

	public void save() {
		try {
			Files.createDirectories(FILE.getParent());
			Files.writeString(FILE, GSON.toJson(this), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// settings are cosmetic
		}
	}
}
