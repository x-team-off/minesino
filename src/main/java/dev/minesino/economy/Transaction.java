package dev.minesino.economy;

import java.util.UUID;

/**
 * Immutable ledger entry. {@code delta} is the signed change of the balance,
 * {@code balanceAfter} the balance right after it was applied.
 *
 * @param opId idempotency key; a second operation with the same key is never applied
 * @param detail short machine-readable description, e.g. {@code roulette} or {@code minecraft:iron_ingot x64}
 * @param outcome round outcome for casino entries ({@code win}, {@code loss}, {@code push}) or empty
 */
public record Transaction(long id, long time, UUID player, TxType type, long delta, long balanceAfter,
						  String detail, String outcome, String opId) {
}
