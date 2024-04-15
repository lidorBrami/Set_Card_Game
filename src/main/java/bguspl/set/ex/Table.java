package bguspl.set.ex;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.concurrent.Semaphore;

import bguspl.set.Env;

/**
 * This class contains the data that is visible to the player.
 *
 * @inv slotToCard[x] == y iff cardToSlot[y] == x
 */
public class Table {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Mapping between a slot and the card placed in it (null if none).
     */
    protected final Integer[] slotToCard; // card per slot (if any)

    /**
     * Mapping between a card and the slot it is in (null if none).
     */
    protected final Integer[] cardToSlot; // slot per card (if any)

    protected Map<Integer, Set<Integer>> slotToPlayers;

    // Semaphore for controlling access to the table's state
    public final Semaphore tableSemaphore = new Semaphore(1, true);

    public final int zero = 0;

    /**
     * Constructor for testing.
     *
     * @param env        - the game environment objects.
     * @param slotToCard - mapping between a slot and the card placed in it (null if
     *                   none).
     * @param cardToSlot - mapping between a card and the slot it is in (null if
     *                   none).
     */
    public Table(Env env, Integer[] slotToCard, Integer[] cardToSlot) {
        this.env = env;
        this.slotToCard = slotToCard;
        this.cardToSlot = cardToSlot;
        this.slotToPlayers = new HashMap<>();
    }

    /**
     * Constructor for actual usage.
     *
     * @param env - the game environment objects.
     */
    public Table(Env env) {

        this(env, new Integer[env.config.tableSize], new Integer[env.config.deckSize]);
    }

    /**
     * This method prints all possible legal sets of cards that are currently on the
     * table.
     */
    public void hints() {
        List<Integer> deck = Arrays.stream(slotToCard).filter(Objects::nonNull).collect(Collectors.toList());
        env.util.findSets(deck, Integer.MAX_VALUE).forEach(set -> {
            StringBuilder sb = new StringBuilder().append("Hint: Set found: ");
            List<Integer> slots = Arrays.stream(set).mapToObj(card -> cardToSlot[card]).sorted()
                    .collect(Collectors.toList());
            int[][] features = env.util.cardsToFeatures(set);
            System.out.println(
                    sb.append("slots: ").append(slots).append(" features: ").append(Arrays.deepToString(features)));
        });
    }

    /**
     * Count the number of cards currently on the table.
     *
     * @return - the number of cards on the table.
     */
    public int countCards() {
        int cards = zero;
        for (Integer card : slotToCard)
            if (card != null)
                ++cards;
        return cards;
    }

    /**
     * Places a card on the table in a grid slot.
     * 
     * @param card - the card id to place in the slot.
     * @param slot - the slot in which the card should be placed.
     *
     * @post - the card placed is on the table, in the assigned slot.
     */
    public void placeCard(int card, int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        cardToSlot[card] = slot;
        slotToCard[slot] = card;
        env.ui.placeCard(card, slot);
    }

    /**
     * Removes a card from a grid slot on the table.
     * 
     * @param slot - the slot from which to remove the card.
     */
    public Set<Integer> removeCard(int slot) {
        try {
            Thread.sleep(env.config.tableDelayMillis);
        } catch (InterruptedException ignored) {
        }
        Integer card = slotToCard[slot];
        if (card != null) {
            slotToCard[slot] = null;
            cardToSlot[card] = null;
            env.ui.removeCard(slot);
            Set<Integer> playerIds = removeAllTokensFromSlot(slot);
            return playerIds;
        }
        return new HashSet<>();
    }

    /**
     * Places a player token on a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot on which to place the token.
     */
    public synchronized void placeToken(int player, int slot) {
        if (slotToCard[slot] != null) { // Ensure there's a card to place a token on
            env.ui.placeToken(player, slot);
            slotToPlayers.computeIfAbsent(slot, k -> new HashSet<>()).add(player);
        }
    }

    /**
     * Removes a token of a player from a grid slot.
     * 
     * @param player - the player the token belongs to.
     * @param slot   - the slot from which to remove the token.
     * @return - true iff a token was successfully removed.
     */
    public synchronized boolean removeToken(int player, int slot) {
        Set<Integer> players = slotToPlayers.get(slot);
        if (players != null && players.remove(player)) {
            env.ui.removeToken(player, slot);
            if (players.isEmpty()) {
                slotToPlayers.remove(slot);
            }
            return true;
        }
        return false;
    }

    public synchronized Set<Integer> removeAllTokensFromSlot(int slot) {
        Set<Integer> playersWithTokens = slotToPlayers.get(slot);
        if (playersWithTokens != null && !playersWithTokens.isEmpty()) {
            for (Integer player : playersWithTokens) {
                env.ui.removeToken(player, slot); // Assuming a UI method to visually remove a token
            }
            slotToPlayers.remove(slot); // Remove the entry to clear all tokens from the slot
            return new HashSet<>(playersWithTokens); // Return a copy to avoid modification exceptions
        }
        return new HashSet<>(); // Return an empty set if no tokens were found
    }

    public boolean hasToken(int id, int slot) {
        Set<Integer> players = slotToPlayers.get(slot);
        if (players != null) {
            return players.contains(id);
        }
        return false; // No token for this player in the specified slot
    }
}
