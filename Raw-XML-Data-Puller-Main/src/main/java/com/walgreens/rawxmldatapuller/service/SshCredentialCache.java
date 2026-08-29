package com.walgreens.rawxmldatapuller.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * In-memory SSH credential store for the duration of an application session.
 *
 * <p>The first Method 3 execution asks the user for their One ID and password.
 * Every subsequent execution within the same session reuses those credentials
 * silently — no dialog.  The cached values are automatically wiped after
 * {@value #IDLE_MINUTES} minutes of inactivity (no Method 3 call), and can be
 * cleared explicitly on app close via {@link #clear()}.</p>
 *
 * <p>Credentials are held in heap memory only — never written to disk, logs, or
 * any external system.</p>
 */
public class SshCredentialCache {

    private static final Logger log          = LoggerFactory.getLogger(SshCredentialCache.class);
    public static final  int    IDLE_MINUTES = 10;

    private final int idleMinutes;

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ssh-cred-expiry");
        t.setDaemon(true);
        return t;
    });

    private String             username;
    private String             password;
    private ScheduledFuture<?> expiryTask;
    private volatile Runnable  onExpiry;
    private volatile Runnable  onStore;

    public SshCredentialCache()             { this(IDLE_MINUTES); }
    public SshCredentialCache(int minutes)  { this.idleMinutes = minutes; }

    /** Called on the scheduler thread when credentials are wiped by the idle timer. */
    public void setOnExpiry(Runnable cb) { this.onExpiry = cb; }
    /** Called on the caller's thread each time new credentials are stored. */
    public void setOnStore(Runnable cb)  { this.onStore  = cb; }

    // ----------------------------------------------------------------
    //  Public API
    // ----------------------------------------------------------------

    /**
     * Returns {@code true} if credentials are currently cached (not yet expired).
     */
    public synchronized boolean isCached() {
        return username != null;
    }

    /**
     * Returns the cached credentials and resets the {@value #IDLE_MINUTES}-minute
     * idle timer.  Returns {@code null} if no credentials are cached.
     *
     * @return {@code String[]{username, password}} or {@code null}
     */
    public synchronized String[] use() {
        if (username == null) return null;
        resetTimer();
        log.debug("SSH credential cache hit — idle timer reset");
        return new String[]{username, password};
    }

    /**
     * Stores credentials and starts the idle timer.  Replaces any previously
     * cached value.
     */
    public synchronized void store(String username, String password) {
        this.username = username;
        this.password = password;
        resetTimer();
        log.info("SSH credentials cached — idle expiry in {}min", idleMinutes);
        Runnable cb = onStore;
        if (cb != null) cb.run();
    }

    /**
     * Clears all cached credentials immediately.  Call this on application close
     * to release sensitive data as early as possible.
     */
    public synchronized void clear() {
        username = null;
        password = null;
        if (expiryTask != null) { expiryTask.cancel(false); expiryTask = null; }
        log.info("SSH credentials cleared");
    }

    // ----------------------------------------------------------------

    private void resetTimer() {
        if (expiryTask != null) expiryTask.cancel(false);
        expiryTask = scheduler.schedule(() -> {
            log.info("SSH credentials expired after {}min idle", idleMinutes);
            clear();
            Runnable cb = onExpiry;
            if (cb != null) cb.run();
        }, idleMinutes, TimeUnit.MINUTES);
    }
}

