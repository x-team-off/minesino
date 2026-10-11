package dev.minesino.economy;

/** Category of a ledger entry. Used for history filters and statistics. */
public enum TxType {
	BET,
	PAYOUT,
	REFUND,
	ITEM_SALE,
	MOB_SALE,
	PURCHASE,
	ADMIN_SET,
	ADMIN_ADD,
	ADMIN_REMOVE,
	INITIAL;

	/** Filter groups shown in the history screen. */
	public enum Group { ALL, CASINO, SALES, PURCHASES, ADMIN }

	public Group group() {
		return switch (this) {
			case BET, PAYOUT, REFUND -> Group.CASINO;
			case ITEM_SALE, MOB_SALE -> Group.SALES;
			case PURCHASE -> Group.PURCHASES;
			case ADMIN_SET, ADMIN_ADD, ADMIN_REMOVE, INITIAL -> Group.ADMIN;
		};
	}
}
