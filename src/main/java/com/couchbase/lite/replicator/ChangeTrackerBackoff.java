package com.couchbase.lite.replicator;

import com.couchbase.lite.internal.InterfaceAudience;
import com.couchbase.lite.util.Log;

/**
 * @exclude
 */
@InterfaceAudience.Private
public class ChangeTrackerBackoff {

    private static int MAX_SLEEP_MILLISECONDS = 5 * 60 * 1000;  // 5 mins
    private int numAttempts = 0;

    public void resetBackoff() {
        numAttempts = 0;
    }

    public int getSleepMilliseconds() {

        int result = (int) (Math.pow(numAttempts, 2) - 1) / 2;

        result *= 100;

        if (result < MAX_SLEEP_MILLISECONDS) {
            increaseBackoff();
        }

        result = Math.abs(result);

        return result;
    }

    public void sleepAppropriateAmountOfTime() {
        try {
            int sleepMilliseconds = getSleepMilliseconds();
            if (sleepMilliseconds > 0) {
                Log.d(Log.TAG_CHANGE_TRACKER, "%s: sleeping for %d", this, sleepMilliseconds);
                Thread.sleep(sleepMilliseconds);
            }
        } catch (InterruptedException e1) {
        }
    }

    private void increaseBackoff() {
        numAttempts += 1;
    }

    public int getNumAttempts() {
        return numAttempts;
    }
}
