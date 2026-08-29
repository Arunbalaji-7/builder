package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Reads and writes admin-editable settings (Oracle DB, SSH, links) to the
 * MySQL {@code raw_xml_data_puller_app_config} table.
 *
 * <p>Connection params are stored at construction time to avoid any circular
 * dependency with {@link com.walgreens.rawxmlpuller.util.ConfigLoader}.</p>
 *
 * <p><b>Password encryption</b>: any key listed in {@link #ENCRYPTED_KEYS}
 * is transparently AES-256-GCM encrypted on write and decrypted on read using
 * the {@code app.encryption.key} from {@code application.properties}.
 * Plaintext values (e.g. migrated from application.properties) are returned
 * as-is and encrypted on the next save.</p>
 */
public class AppConfigService {

    private static final Logger log = LoggerFactory.getLogger(AppConfigService.class);

    /** Keys whose values are stored encrypted in MySQL. */
    private static final Set<String> ENCRYPTED_KEYS = Set.of(
            "erx.db.password",
            "icplus.db.password",
            "vision.db.password",
            "ssh.business.password",
            "metrics.server.password"
    );

    private final String jdbcUrl;
    private final String dbUser;
    private final String dbPass;
    private final String encryptionKey;

    public AppConfigService(String host, int port, String dbName,
                            String user, String pass, String encryptionKey) {
        this.jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                host, port, dbName);
        this.dbUser        = user;
        this.dbPass        = pass;
        this.encryptionKey = encryptionKey;
    }

    private Connection getConn() throws SQLException {
        return DriverManager.getConnection(jdbcUrl, dbUser, dbPass);
    }

    /**
     * Returns a config value from MySQL {@code raw_xml_data_puller_app_config}.
     * Password keys are automatically decrypted before being returned.
     * Returns {@code null} if the key is absent.
     */
    public String get(String key) {
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT config_value FROM raw_xml_data_puller_app_config WHERE config_key = ?")) {
            ps.setString(1, key);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) {
                    String value = rs.getString("config_value");
                    return decrypt(key, value);
                }
            }
        } catch (Exception e) {
            log.warn("Could not read config key '{}' from DB", key, e);
        }
        return null;
    }

    /**
     * Loads all config entries from MySQL into a map.
     * Password keys are automatically decrypted.
     */
    public Map<String, String> getAll() {
        Map<String, String> map = new HashMap<>();
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT config_key, config_value FROM raw_xml_data_puller_app_config");
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                String key   = rs.getString("config_key");
                String value = rs.getString("config_value");
                if (value != null) map.put(key, decrypt(key, value));
            }
        } catch (Exception e) {
            log.warn("Could not load raw_xml_data_puller_app_config from DB", e);
        }
        return map;
    }

    /**
     * Inserts entries that do not already have a row in {@code raw_xml_data_puller_app_config}
     * ({@code INSERT IGNORE}). Used on first login to seed the table from
     * {@code application.properties} without overwriting any admin changes.
     */
    public void saveIfAbsent(Map<String, String> configs) throws SQLException {
        String sql = "INSERT IGNORE INTO raw_xml_data_puller_app_config (config_key, config_value) VALUES (?, ?)";
        try (Connection conn = getConn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<String, String> e : configs.entrySet()) {
                    String key   = e.getKey();
                    String value = encrypt(key, e.getValue());
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                log.info("Seeded {} default config entries into DB (skipped existing)", configs.size());
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    /**
     * Saves multiple config entries in a single transaction (upsert).
     * Password keys are automatically encrypted before being stored.
     */
    public void saveAll(Map<String, String> configs) throws SQLException {
        String sql = "INSERT INTO raw_xml_data_puller_app_config (config_key, config_value) VALUES (?, ?) "
                + "ON DUPLICATE KEY UPDATE config_value = VALUES(config_value)";
        try (Connection conn = getConn()) {
            conn.setAutoCommit(false);
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                for (Map.Entry<String, String> e : configs.entrySet()) {
                    String key   = e.getKey();
                    String value = encrypt(key, e.getValue());
                    ps.setString(1, key);
                    ps.setString(2, value);
                    ps.addBatch();
                }
                ps.executeBatch();
                conn.commit();
                log.info("Saved {} config entries to DB", configs.size());
            } catch (SQLException ex) {
                conn.rollback();
                throw ex;
            }
        }
    }

    // ----------------------------------------------------------------
    //  Crypto helpers
    // ----------------------------------------------------------------

    private String encrypt(String key, String value) {
        if (ENCRYPTED_KEYS.contains(key) && !CryptoUtil.isEncrypted(value))
            return CryptoUtil.encrypt(value, encryptionKey);
        return value;
    }

    private String decrypt(String key, String value) {
        if (ENCRYPTED_KEYS.contains(key) && CryptoUtil.isEncrypted(value))
            return CryptoUtil.decrypt(value, encryptionKey);
        return value;
    }
}

