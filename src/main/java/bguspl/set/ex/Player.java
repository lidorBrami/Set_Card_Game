package bguspl.set.ex;

import java.util.Random;
import bguspl.set.Env;
import java.util.logging.Level;
import java.util.concurrent.BlockingQueue;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.Queue;
import java.util.ArrayList;
import java.util.LinkedList;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */

    private long timeOfSleep;

    private int score;

    private final Dealer dealer;

    private final int capacity;

    protected BlockingQueue<Integer> claimedSlots;

    protected Queue<Integer> playerSet;

    protected final Object lock = new Object();

    protected volatile boolean isSleeping;

    protected boolean check;

    private final int zero=0;
    private final long ten=10;
    private final long thirty=30;
    private final long oneThousand=1000;
    private final int five = 5; 

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.capacity = env.config.featureSize;
        this.claimedSlots = new ArrayBlockingQueue<Integer>(this.capacity);
        this.score = zero;
        this.dealer = dealer;
        isSleeping = false;
        timeOfSleep = zero;
        check = false;
        terminate = false;
        playerSet = new LinkedList<Integer>();
    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("Starting player thread: " + playerThread.getName());
        if (!human) {
            createArtificialIntelligence();
        }

        while (!terminate) {
            if (human) {
                if (timeOfSleep > zero) { // player in freeze time
                    for (long i = timeOfSleep / oneThousand; i > zero; i--) {
                        env.ui.setFreeze(id, i * oneThousand);
                        try {
                            Thread.sleep(oneThousand);
                        } catch (InterruptedException e) {
                        }
                    }
                    env.ui.setFreeze(id, zero);
                    isSleeping = false;
                    timeOfSleep = zero;
                }
            

                while (claimedSlots.size() > zero && !dealer.lockGame && !terminate && timeOfSleep == zero) {
                    consume();
                }
                while (playerSet.size() == capacity && !check) {
                    try{
                        Thread.sleep(thirty);
                    }
                    catch(InterruptedException e){}
            }
            //
                while (playerSet.size() == capacity && !check) {
                    try{
                        Thread.sleep(thirty);
                    }
                    catch(InterruptedException e){}
                    }
            }
        }

        if (!human) {
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");

    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                if (timeOfSleep > zero) { // player in freeze time
                    for (long i = timeOfSleep / oneThousand; i > zero; i--) {
                        env.ui.setFreeze(id, i * oneThousand);
                        try {
                            Thread.sleep(oneThousand);
                        } catch (InterruptedException e) {
                        }
                    }
                    env.ui.setFreeze(id, zero);
                    timeOfSleep = zero;
                }
                while (!dealer.lockGame && !terminate && timeOfSleep == zero) {
                    try{
                        Thread.sleep(five);//
                        table.tableSemaphore.acquire();
                        if(playerSet.size() == env.config.featureSize && check || playerSet.size()<env.config.featureSize){
                            aiPress();
                        }
                        table.tableSemaphore.release();
                    }
                    catch(InterruptedException e){
                    }
                    table.tableSemaphore.release();
                    while (playerSet.size() == capacity && !check) {
                            try{
                                Thread.sleep(thirty);
                            }
                            catch(InterruptedException e){}
                    }
                }
            }
            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    private void aiPress() {
        Random random = new Random();
        int randomNum = random.nextInt(env.config.tableSize);
        keyPressed(randomNum);
        consume();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        if (aiThread != null) {
            aiThread.interrupt();
        }
        playerThread.interrupt();
        terminate = true;
    }

    private void consume() {
            // Thread.sleep(100);
            Integer slot = claimedSlots.poll();
            if(slot == null) return;
            if (playerSet.size() == capacity && check) {
                if (table.hasToken(id, slot)) {
                    removeFromQ(slot);
                    table.removeToken(id, slot);
                    return;
                }
                return;
            }    
            if (table.hasToken(id, slot)) {
                removeFromQ(slot);
                table.removeToken(id, slot);
            } else if (claimedSlots.size() < capacity) {
                if(!dealer.lockGame)
                     playerSet.add(slot);
                table.placeToken(id, slot);
            }

            if ((playerSet.size() == env.config.featureSize)) {
                check = false;
                synchronized(dealer){
                    dealer.notifyAll();
                }
                dealer.playerCheck.add(id);
                //the player check .add was here
                // if(dealer.playerCheck.contains(id)){
                //     synchronized (lock) {
                //         lock.wait();;
                //     }
                // }
            }
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (((claimedSlots.size() == capacity) && !claimedSlots.contains(slot)) || table.slotToCard[slot] == null || dealer.lockGame || playerSet.size() == capacity&& !check){
            return;
        }
        else if (claimedSlots.size() < capacity) {
            if(playerSet.size() == capacity&&!check || timeOfSleep>zero){
                return;
            }
            claimedSlots.add(slot);
        }
    }
    // check to delete the synchronized
    public List<Integer> getPlayerSet() {
        LinkedList<Integer> temp = new LinkedList<Integer>();
        for (Integer i : playerSet) {
            temp.add(i);
        }
        return temp;
    }

    public void clearClaimedSlots() {
        claimedSlots.clear();
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
        // TODO implement
        score++;
        // int ignored = table.countCards(); // this part is just for demonstration in
        // the unit tests
        env.ui.setScore(id, score);
        timeOfSleep = env.config.pointFreezeMillis;
        isSleeping = false;
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName());
        check = true;
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        // TODO implement
        timeOfSleep = env.config.penaltyFreezeMillis;
        // change to true
        isSleeping = false;
        check = true;

    }

    public int score() {
        return score;
    }

    public void removeFromQ(int token) {
        if (playerSet.contains(token))
            playerSet.remove(token);
    }
}
