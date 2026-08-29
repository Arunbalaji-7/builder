package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.DbConfig;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;

/**
 * Factory and health-check service for Oracle JDBC connections.
 *
 * <p>Connection parameters are read from {@link ConfigLoader} on every call,
 * meaning configuration changes (via Settings → Reload) take effect
 * immediately without restarting the app.</p>
 *
 * <p>The Oracle JDBC driver is registered via {@link Class#forName} in a static
 * initialiser. A login timeout of 15 seconds is applied to prevent indefinite
 * hanging on unreachable hosts.</p>
 *
 * <h3>Resource management</h3>
 * Callers are responsible for closing the returned {@link Connection}
 * (use try-with-resources).
 */
public class DatabaseService {

    private static final Logger log = LoggerFactory.getLogger(DatabaseService.class);
    private static final int LOGIN_TIMEOUT_SECONDS = 15;

    /** ORA-02391: exceeded simultaneous sessions per user limit. */
    private static final int ORA_SESSION_LIMIT = 2391;
    private static final int MAX_RETRIES       = 3;
    private static final long RETRY_DELAY_MS   = 800;

    static {
        try { Class.forName("oracle.jdbc.OracleDriver"); }
        catch (ClassNotFoundException e) { log.error("Oracle JDBC driver not found on classpath", e); }
    }

    // ----------------------------------------------------------------
    //  Connection factories
    // ----------------------------------------------------------------

    /**
     * Opens a new connection to the <b>eRx</b> database.
     *
     * @return an open {@link Connection}; caller must close it
     * @throws SQLException if the connection cannot be established
     */
    public Connection getErxConnection() throws SQLException {
        return connect(ConfigLoader.getInstance().getErxDbConfig());
    }

    /**
     * Opens a new connection to the <b>IC+</b> database.
     *
     * @return an open {@link Connection}; caller must close it
     * @throws SQLException if the connection cannot be established
     */
    public Connection getIcPlusConnection() throws SQLException {
        return connect(ConfigLoader.getInstance().getIcPlusDbConfig());
    }

    /**
     * Opens a new connection to the <b>Vision</b> database.
     *
     * @return an open {@link Connection}; caller must close it
     * @throws SQLException if the connection cannot be established
     */
    public Connection getVisionConnection() throws SQLException {
        return connect(ConfigLoader.getInstance().getVisionDbConfig());
    }

    // ----------------------------------------------------------------
    //  Health check
    // ----------------------------------------------------------------

    /**
     * Tests whether a connection can be established and a trivial query executed.
     * Never throws — returns {@code false} on any error.
     *
     * @param cfg the database configuration to test
     * @return {@code true} if {@code SELECT 1 FROM DUAL} succeeds
     */
    public boolean testConnection(DbConfig cfg) {
        try (Connection conn = connect(cfg);
             Statement  st   = conn.createStatement()) {
            st.execute("SELECT 1 FROM DUAL");
            log.debug("Connection test OK: {}", cfg.getName());
            return true;
        } catch (Exception e) {
            log.debug("Connection test FAILED for {}: {}", cfg.getName(), e.getMessage());
            return false;
        }
    }

    // ----------------------------------------------------------------
    //  Internal helpers
    // ----------------------------------------------------------------

    private Connection connect(DbConfig cfg) throws SQLException {
        DriverManager.setLoginTimeout(LOGIN_TIMEOUT_SECONDS);
        SQLException last = null;
        for (int attempt = 1; attempt <= MAX_RETRIES; attempt++) {
            try {
                log.debug("Connecting to {} (attempt {}/{})", cfg.getName(), attempt, MAX_RETRIES);
                return DriverManager.getConnection(cfg.getJdbcUrl(), cfg.getUsername(), cfg.getPassword());
            } catch (SQLException e) {
                last = e;
                if (e.getErrorCode() == ORA_SESSION_LIMIT && attempt < MAX_RETRIES) {
                    log.warn("ORA-02391 for {} on attempt {}/{} — retrying in {}ms",
                            cfg.getName(), attempt, MAX_RETRIES, RETRY_DELAY_MS * attempt);
                    try { Thread.sleep(RETRY_DELAY_MS * attempt); }
                    catch (InterruptedException ie) { Thread.currentThread().interrupt(); break; }
                } else {
                    break;
                }
            }
        }
        String msg = (last != null && last.getErrorCode() == ORA_SESSION_LIMIT)
                ? "Oracle session limit reached for " + cfg.getName()
                  + ". Another connection is still open. Please wait a moment and retry."
                : "Cannot connect to " + cfg.getName() + ": " + (last != null ? last.getMessage() : "unknown error");
        log.warn(msg);
        throw new SQLException(msg, last);
    }

    /**
     * Reads an Oracle CLOB column into a Java {@link String}.
     *
     * @param clob the CLOB to read; {@code null} returns {@code null}
     * @return the CLOB content as a string
     * @throws Exception if an I/O error occurs while reading the CLOB stream
     */
    public static String clobToString(Clob clob) throws Exception {
        if (clob == null) return null;
        StringBuilder sb = new StringBuilder();
        try (java.io.Reader r = clob.getCharacterStream()) {
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) != -1) sb.append(buf, 0, n);
        }
        return sb.toString();
    }
}

