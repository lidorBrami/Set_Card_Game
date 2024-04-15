package bguspl.set.ex;

import bguspl.set.Env;
import bguspl.set.Main;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    private final int second = 1000;

    private Thread[] playerThreads;

    public volatile boolean lockGame;

    private boolean needFinish = false;

    //
    private final int zero=0;
    private final int one=1;
    private final int negativeOne=-1;
    private final long ten=10;
    //

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    public Queue<Integer> playerCheck;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(zero, env.config.deckSize).boxed().collect(Collectors.toList());
        playerCheck = new LinkedList<>();
        playerThreads = new Thread[env.config.players];
        lockGame = false;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */

    @Override
    public void run() {
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        for (int i = zero; i < players.length; i++) {
            playerThreads[i] = new Thread(players[i]);
            playerThreads[i].start();
        }
        while (!shouldFinish()) {
            lockGame();
            try {
                table.tableSemaphore.acquire();
                refillTableWithNewCards();
                table.tableSemaphore.release();
            } catch (InterruptedException e) {
            }
            table.tableSemaphore.release();
            updateTimerDisplay(true);
            unlockGame();
            needFinish = false;
            timerLoop();
            try {
                table.tableSemaphore.acquire();

                removeAllCardsFromTable();
                table.tableSemaphore.release();
            } catch (InterruptedException e) {
            }
            table.tableSemaphore.release();
            lockGame();
        }
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    public void lockGame() {
        lockGame = true;
    }

    public void unlockGame() {
        lockGame = false;
    }

    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() <= reshuffleTime && !needFinish) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            try {
                table.tableSemaphore.acquire();
                lockGame();
                removeCardsFromTable();
                placeCardsOnTable();
                table.tableSemaphore.release();
            } catch (InterruptedException e) {
            }
            table.tableSemaphore.release();
            unlockGame();
        }
    }

    private Map<Integer, List<Integer>> groupSlots(List<Integer> slotsClaimedList) {
        return slotsClaimedList.stream().collect(Collectors.groupingBy(slot -> slot % env.config.tableSize/*12*/));
    }

    

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        this.terminate = true;
        for (int i = players.length - one; i >= zero; i--) {
            try {
                players[i].terminate();
                playerThreads[i].join();
            } catch (InterruptedException e) {
            }
        }
    }

    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, one).isEmpty();
    }

    private void refillTableWithNewCards() {
        Collections.shuffle(deck);
        // Iterate through each slot on the table and place a new card from the deck
        for (int slot = zero; slot < env.config.tableSize && !deck.isEmpty(); slot++) {
            if (!deck.isEmpty()) { // Check if the deck is not empty before attempting to remove a card
                int cardIndex = deck.remove(zero); // Removes a card from the top of the deck
                table.placeCard(cardIndex, slot); // Place the card on the table at the current slot
            }
        }
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private synchronized void removeCardsFromTable() {
        while (!playerCheck.isEmpty()) {
            Integer playerId = playerCheck.poll();
            if (playerId != null) {
                processPlayerClaim(playerId);
            }
        }
    }

    private void processPlayerClaim(Integer playerId) {
        Player player = players[playerId];
        List<Integer> slotsClaimedList = player.getPlayerSet();
        Map<Integer, List<Integer>> groupedSlots = groupSlots(slotsClaimedList);
        int[] slotsClaimed = new int[slotsClaimedList.size()];
        for (int i = zero; i < slotsClaimed.length; i++) {
            if (slotsClaimedList.get(zero) == null) {
            } else {
                slotsClaimed[i] = slotsClaimedList.remove(zero);
            }
        }
        int[] cardsClaimed = convertSlotsToCards(slotsClaimed);
        boolean isLegal = true;
        for (int i = zero; i < cardsClaimed.length; i++) {
            if (cardsClaimed[i] == negativeOne) {
                isLegal = false;
                break;
            }
        }
        if (groupedSlots.size() == env.config.featureSize && isLegal && cardsClaimed.length == env.config.featureSize) { // Ensure
                                                                                                                         // player
                                                                                                                         // //
                                                                                                                         // //
                                                                                                                         // slots
            boolean isSet = env.util.testSet(cardsClaimed);
            if (isSet && !checkDuplicates(cardsClaimed)) {                
                removeClaimedCardsAndTokens(slotsClaimed, playerId);
                player.point(); // Award point
                player.playerSet.clear();
                updateTimerDisplay(true);

            }

            else {
                player.penalty(); // Penalize player
                

            }
        }

    }

    public static boolean checkDuplicates(int[] arr) {
        HashSet<Integer> seen = new HashSet<>();
        for (int element : arr) {
            if (seen.contains(element)) {
                return true; // Found a duplicate
            }
            seen.add(element);
        }
        return false; // No duplicates found
    }

    private int[] convertSlotsToCards(int[] slots) {
        int[] cards = new int[slots.length];
        for (int i = zero; i < slots.length; i++) {
            if (slots[i] >= zero && slots[i] < table.slotToCard.length && table.slotToCard[slots[i]] != null)
                cards[i] = table.slotToCard[slots[i]]; // Use slots[i] to access the correct slot
            else {
                cards[i] = negativeOne;

            }
        }

        return cards;
    }

    private void removeClaimedCardsAndTokens(int[] slots, int id) {
        // table.tableSemaphore.acquire();
        for (int slot : slots) {
            // Remove the card from the specified slot on the table.
            Set<Integer> playerIds = table.removeCard(slot);
            // For each player ID, remove the slot from their claimed slots.
            for (Integer playerId : playerIds) {
                Player player = players[playerId]; // Obtain the player instance by ID.
                if (player != null && player.id != id) {
                    player.removeFromQ(slot); // Remove the slot from the player's claimed slots.
                    playerCheck.remove(playerId);
                }
            }
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {
        for (int slot = zero; slot < env.config.tableSize; slot++) {

            if (table.slotToCard[slot] == null && !deck.isEmpty()) { // Check if the slot is empty and the deck is
                                                                     // not
                                                                     // empty

                int cardIndex = deck.remove(zero); // Removes a card from the deck
                table.placeCard(cardIndex, slot); // Place card on the table at the empty slot
                // cardsNeeded--; // Decrease the number of cards needed
            }
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        long delta = reshuffleTime - System.currentTimeMillis();
        if (delta <= env.config.turnTimeoutWarningMillis) {
            try {
                synchronized (this) {
                    wait(ten);
                }
            } catch (InterruptedException e) {
            }
        } else {
            try {
                synchronized (this) {
                    wait(second);
                }
            } catch (InterruptedException e) {
            }
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        //long zero = 0;
        if(env.config.turnTimeoutMillis < zero){
            LinkedList <Integer> cards = new LinkedList<>();
            for(int i = zero; i < table.slotToCard.length; i++){
                if(table.slotToCard[i] != null)
                    cards.add(table.slotToCard[i]);
            }
            if(env.util.findSets(cards, one).isEmpty())
                needFinish = true;
            return;
        }
        if (reset) {
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            env.ui.setCountdown(env.config.turnTimeoutMillis, false);
        }
        // chack when it need to be false
        else {
            if (reshuffleTime - System.currentTimeMillis() <= env.config.turnTimeoutWarningMillis) {
                if (reshuffleTime - System.currentTimeMillis() < zero)
                    return;
                else {
                    env.ui.setCountdown(Max(reshuffleTime - System.currentTimeMillis(), zero), true);
                    if(env.config.hints)
                        table.hints();
                }
            } else {
                env.ui.setCountdown(reshuffleTime - System.currentTimeMillis(), false);
            }
        }
    }

    public long Max(long a, long b) {
        return a > b ? a : b; // Return the larger of the two values
    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {
        Integer[] slot = table.slotToCard;
        for (int i = zero; i < slot.length; i++) {
            if (slot[i] != null) {
                int card = table.slotToCard[i];
                // if(!deck.contains(card))
                deck.add(card);
                table.removeCard(i);
            }
        }
        for (Player p : players) {
            p.playerSet.clear();
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */

    // check if thge numbet of player is limited to 2 else change the implemention
    private void announceWinners() {
        int highestScore = Integer.MIN_VALUE;
        List<Integer> winners = new ArrayList<>();

        // Find the highest score
        for (Player player : players) {
            if (player.score() > highestScore) {
                highestScore = player.score();
                winners.clear(); // Clear previous winners as a higher score is found
                winners.add(player.id); // Add player ID to winners list
            } else if (player.score() == highestScore) {
                winners.add(player.id); // Add player ID to winners list for a tie
            }
        }

        // Convert winners list to array
        int[] winnerIds = winners.stream().mapToInt(i -> i).toArray();

        // Call the UI method to announce winner(s)
        env.ui.announceWinner(winnerIds);
        terminate();

    }
}