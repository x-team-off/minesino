package dev.minesino.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads JSON configuration that may contain {@code //} comments, and writes files annotated with
 * explanations. Existing files are never overwritten; an always-fresh {@code *.example.json}
 * next to them documents every option.
 */
public final class ConfigIO {
	public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
	private static final Pattern KEY = Pattern.compile("^(\\s*)\"([^\"]+)\"\\s*:\\s*(.*)$");

	private ConfigIO() {}

	public static JsonElement parseLenient(Reader reader) {
		JsonReader jr = new JsonReader(reader);
		jr.setStrictness(Strictness.LENIENT);
		return JsonParser.parseReader(jr);
	}

	/**
	 * Loads {@code type} from {@code file}. When the file is missing it is created with defaults.
	 * When it is malformed, defaults are used for this session and the broken file is left untouched.
	 */
	public static <T> T load(Path file, Class<T> type, T defaults, Map<String, String> comments, Consumer<String> warn) {
		try {
			Files.createDirectories(file.getParent());
			Path example = file.resolveSibling(file.getFileName().toString().replace(".json", ".example.json"));
			Files.writeString(example, annotate(GSON.toJson(defaults), comments), StandardCharsets.UTF_8);
			if (!Files.exists(file)) {
				Files.writeString(file, annotate(GSON.toJson(defaults), comments), StandardCharsets.UTF_8);
				return defaults;
			}
			String text = Files.readString(file, StandardCharsets.UTF_8);
			JsonElement tree = parseLenient(new StringReader(text));
			T value = GSON.fromJson(tree, type);
			return value == null ? defaults : value;
		} catch (IOException | RuntimeException e) {
			warn.accept("Could not read " + file.getFileName() + " (" + e.getMessage() + "); using safe defaults until it is fixed");
			return defaults;
		}
	}

	/** Inserts {@code // comment} lines above keys. Comment keys are dotted paths, e.g. {@code casino.minBet}. */
	public static String annotate(String json, Map<String, String> comments) {
		StringBuilder out = new StringBuilder();
		Deque<String> path = new ArrayDeque<>();
		for (String line : json.split("\n")) {
			String trimmed = line.trim();
			if (trimmed.startsWith("}") || trimmed.startsWith("]")) {
				if (!path.isEmpty()) path.pop();
			}
			Matcher m = KEY.matcher(line);
			if (m.matches()) {
				String key = m.group(2);
				String full = path.isEmpty() ? key : String.join(".", reversed(path)) + "." + key;
				String c = comments.get(full);
				if (c != null) {
					for (String cl : c.split("\n")) out.append(m.group(1)).append("// ").append(cl).append('\n');
				}
				String rest = m.group(3).trim();
				if (rest.startsWith("{") && !rest.startsWith("{}")) path.push(key);
				else if (rest.startsWith("[") && !rest.startsWith("[]")) path.push(key);
			} else if ((trimmed.equals("{") || trimmed.equals("[")) && !out.isEmpty()) {
				path.push("#");
			}
			out.append(line).append('\n');
		}
		return out.toString();
	}

	private static Iterable<String> reversed(Deque<String> d) {
		java.util.List<String> l = new java.util.ArrayList<>(d);
		java.util.Collections.reverse(l);
		return l;
	}

	public static Map<String, String> mainComments() {
		Map<String, String> c = new LinkedHashMap<>();
		c.put("currency", "MineSino server configuration. Lines starting with // are comments.\nMoney values are whole currency units with up to 2 decimals (e.g. 12.5).\nApply changes with /minesino reload - balances and active rounds are kept.");
		c.put("currency.symbol", "Currency symbol shown after amounts.");
		c.put("currency.displayDecimals", "Maximum decimal places shown in the UI (0..2); trailing zeros are hidden. Storage is always exact to 0.01.");
		c.put("currency.startingBalance", "Balance given to a player when their account is created.");
		c.put("casino.minBet", "Minimum and maximum stake per round (per spin for roulette).");
		c.put("casino.quickBets", "Quick bet buttons shown in every game.");
		c.put("casino.enabledGames", "Disable a game by setting it to false. Disabled games are hidden in the tablet.");
		c.put("casino.slots", "Slots: 3 reels x 3 rows, 5 paylines. Weights must sum to 32 (stops per reel).\npay3 = line multiplier for 3 identical symbols; each line stakes bet/5.");
		c.put("casino.blackjack", "Blackjack against the dealer. Natural pays numerator:denominator (default 3:2).");
		c.put("casino.plinko", "Plinko bin multipliers per <risk>_<rows>; each list needs rows+1 values.");
		c.put("casino.dice", "Dice (d6): win when the die shows one of the chosen faces.\nPayout multiplier = 6/k * (1 - houseEdgePercent/100).");
		c.put("casino.upgrade", "Upgrade: success chance = (1 - commission) / multiplier.");
		c.put("selling.allowModifiedItems", "Allow selling enchanted/damaged/renamed items (they are valued at base item price).");
		c.put("selling.sellMultiplier", "Factor applied to catalogue prices when a player sells.");
		c.put("shop.buyMultiplier", "Shop price = catalogue price * buyMultiplier. Must stay >= sellMultiplier.");
		c.put("shop.allowedNamespaces", "Only items from these namespaces are offered automatically. Modded items need allowItems.");
		c.put("shop.disabledCategories", "Price categories hidden from the shop (FORBIDDEN is always hidden).");
		c.put("mobs", "Selling mobs with Shift + right click.");
		c.put("mobs.requireTablet", "Require the tablet slot to be selected when selling mobs.");
		c.put("mobs.bredMultiplier", "Price factor for bred animals; keeps breeding farms from printing money.");
		c.put("mobs.naturalGolemMultiplier", "Price factor for iron golems not built by a player (villages, eggs, iron farms). 1.0 = full recipe price; e.g. 0.1 taxes iron farms.");
		c.put("spawnEggs", "Spawn eggs available in the shop. Entities not listed in 'allowed' are never sold.");
		c.put("permissions.adminLevel", "Operator permission level needed for /balance set|add|remove and /minesino reload.");
		c.put("permissions.adminPlayers", "UUIDs of extra players allowed to use admin commands.");
		c.put("limits", "Anti-spam limits applied to every economic request.");
		c.put("storage.historyLimit", "Entries kept per player in the history screen.");
		c.put("storage.backups", "Number of rotated backups of economy.json.");
		c.put("logging", "Journal files are written to <world>/minesino/journal/.");
		return c;
	}
}
