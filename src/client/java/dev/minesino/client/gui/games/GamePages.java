package dev.minesino.client.gui.games;

import java.util.List;

/** Client pages for the registered games, in lobby order. */
public final class GamePages {
	public static final List<String> ORDER = List.of(
		"roulette", "slots", "blackjack", "plinko", "crash", "mines", "dice", "coinflip", "upgrade", "wheel", "hilo", "keno");

	private GamePages() {}

	public static GamePage create(String id) {
		return switch (id) {
			case "roulette" -> new RoulettePage();
			case "slots" -> new SlotsPage();
			case "blackjack" -> new BlackjackPage();
			case "plinko" -> new PlinkoPage();
			case "crash" -> new CrashPage();
			case "mines" -> new MinesPage();
			case "dice" -> new DicePage();
			case "coinflip" -> new CoinflipPage();
			case "upgrade" -> new UpgradePage();
			case "wheel" -> new WheelPage();
			case "hilo" -> new HiLoPage();
			case "keno" -> new KenoPage();
			default -> throw new IllegalArgumentException("Unknown game " + id);
		};
	}
}
