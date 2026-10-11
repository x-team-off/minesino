package dev.minesino.economy;

/**
 * Informational per-player statistics. Never used as a source of truth for money:
 * balances live only in {@link EconomyManager}.
 */
public final class PlayerStats {
	public long roundsPlayed;
	public long wins;
	public long losses;
	public long pushes;
	public long totalWagered;
	public long totalPaidOut;
	public long itemsSold;
	public long itemSaleIncome;
	public long mobsSold;
	public long mobSaleIncome;
	public long purchases;
	public long purchaseSpent;
	public long biggestWin;

	public long netCasino() {
		return totalPaidOut - totalWagered;
	}

	public PlayerStats copy() {
		PlayerStats s = new PlayerStats();
		s.roundsPlayed = roundsPlayed; s.wins = wins; s.losses = losses; s.pushes = pushes;
		s.totalWagered = totalWagered; s.totalPaidOut = totalPaidOut; s.itemsSold = itemsSold;
		s.itemSaleIncome = itemSaleIncome; s.mobsSold = mobsSold; s.mobSaleIncome = mobSaleIncome;
		s.purchases = purchases; s.purchaseSpent = purchaseSpent; s.biggestWin = biggestWin;
		return s;
	}
}
