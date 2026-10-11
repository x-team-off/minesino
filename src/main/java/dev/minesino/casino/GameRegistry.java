package dev.minesino.casino;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Registered games by id, in display order. */
public final class GameRegistry {
	private final Map<String, CasinoGame> games = new LinkedHashMap<>();

	public GameRegistry register(CasinoGame game) {
		if (games.putIfAbsent(game.id(), game) != null) throw new IllegalStateException("Duplicate game id " + game.id());
		return this;
	}

	public CasinoGame get(String id) {
		return games.get(id);
	}

	public Collection<CasinoGame> all() {
		return Collections.unmodifiableCollection(games.values());
	}
}
