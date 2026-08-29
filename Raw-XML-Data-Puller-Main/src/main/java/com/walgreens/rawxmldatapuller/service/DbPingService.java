package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.DbConfig;
import javafx.application.Platform;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * Shared async DB connectivity checker.
 *
 * <p>Eliminates duplicate ping logic between {@code MainController} and
 * {@code SettingsController}.  All threads are daemon threads so they do not
 * block JVM shutdown.  Results are delivered on the JavaFX Application Thread.</p>
 */
public class DbPingService {

    private final DatabaseService db;
    private final ExecutorService executor;

    public DbPingService(DatabaseService db) {
        this.db = db;
        // Single thread: pings run one at a time to avoid exceeding Oracle's
        // SESSIONS_PER_USER limit when multiple DBs are checked simultaneously.
        this.executor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "db-ping");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * Fires a non-blocking connection test.
     * {@code onResult} is called on the FX thread with {@code true} on success.
     */
    public void pingAsync(DbConfig config, Consumer<Boolean> onResult) {
        executor.submit(() -> {
            boolean ok = db.testConnection(config);
            Platform.runLater(() -> onResult.accept(ok));
        });
    }
}
