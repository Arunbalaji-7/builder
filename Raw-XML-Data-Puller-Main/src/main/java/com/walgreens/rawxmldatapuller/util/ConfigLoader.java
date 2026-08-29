package com.walgreens.rawxmldatapuller.util;

import com.walgreens.rawxmldatapuller.model.DbConfig;
import com.walgreens.rawxmldatapuller.service.AppConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.file.*;
import java.util.Properties;

/**
 * Singleton configuration loader for the RX Data Puller application.
 * <p>
 * On first access, {@code ConfigLoader} attempts to load properties from an
 * external {@code config.properties} file located in the JVM working directory.
 * If that file does not exist (or cannot be read), it falls back to the
 * {@code /application.properties} resource bundled on the classpath.
 * </p>
 * <p>
 * The class exposes typed accessors for all database connection parameters
 * (eRx, IC+, and Vision) as well as SSH and hyperlink settings.  Database
 * credentials are read-only from the perspective of the application; only the
 * SSH server/port and SOP/incident link values may be modified at runtime and
 * persisted back to disk via {@link #save()}.
 * </p>
 * <p>
 * All public methods that access the singleton are thread-safe with respect to
 * initialisation ({@link #getInstance()} is {@code synchronized}).
 * </p>
 */
public class ConfigLoader {

    private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);
    private static final String EXTERNAL_CONFIG = "config.properties";
    private static final String CLASSPATH_CONFIG = "/application.properties";

    private static ConfigLoader instance;
    private Properties      props;
    private AppConfigService appConfigService;

    private ConfigLoader() {
        props = new Properties();
        loadConfig();
    }

    /**
     * Returns the singleton {@code ConfigLoader} instance, creating and
     * initialising it on the first call.
     *
     * @return the application-wide {@code ConfigLoader} singleton; never {@code null}
     */
    public static synchronized ConfigLoader getInstance() {
        if (instance == null) instance = new ConfigLoader();
        return instance;
    }

    private void loadConfig() {
        Path external = Paths.get(EXTERNAL_CONFIG);
        if (Files.exists(external)) {
            try (InputStream in = Files.newInputStream(external)) {
                props.load(in);
                log.info("Config loaded from {}", external.toAbsolutePath());
                return;
            } catch (IOException e) {
                log.warn("Could not read {}, falling back to classpath", external, e);
            }
        }
        try (InputStream in = getClass().getResourceAsStream(CLASSPATH_CONFIG)) {
            if (in != null) {
                props.load(in);
                log.info("Config loaded from classpath {}", CLASSPATH_CONFIG);
            }
        } catch (IOException e) {
            log.error("Failed to load classpath config", e);
        }
    }

    /**
     * Reloads the configuration from disk, discarding all in-memory values.
     * <p>
     * The same source-priority rules apply as at construction time: an external
     * {@code config.properties} file takes precedence over the classpath
     * {@code application.properties}.  Any unsaved in-memory changes made via
     * setters will be lost.
     * </p>
     */
    public void reload() {
        props = new Properties();
        loadConfig();
    }

    /**
     * Persists the current in-memory configuration to {@code config.properties}
     * in the JVM working directory, creating the file if it does not yet exist.
     * <p>
     * Subsequent launches (or calls to {@link #reload()}) will pick up this file
     * in preference to the classpath {@code application.properties}.
     * </p>
     *
     * @throws IOException if the file cannot be written
     */
    public void save() throws IOException {
        try (OutputStream out = new FileOutputStream(EXTERNAL_CONFIG)) {
            props.store(out, "RX Data Puller Configuration");
        }
    }

    // ---- DB configs (read from properties, not user-entered) ----

    /**
     * Returns a {@link DbConfig} populated with the eRx database connection
     * parameters ({@code erx.db.*} keys) from the loaded properties.
     *
     * @return a {@link DbConfig} for the eRx Oracle database; never {@code null}
     */
    public DbConfig getErxDbConfig() {
        return new DbConfig(
                get("erx.db.name",     "eRx DB"),
                get("erx.db.hostname", ""),
                getInt("erx.db.port",  1521),
                get("erx.db.sid",      ""),
                get("erx.db.username", ""),
                get("erx.db.password", "")
        );
    }

    /**
     * Returns a {@link DbConfig} populated with the IC+ database connection
     * parameters ({@code icplus.db.*} keys) from the loaded properties.
     *
     * @return a {@link DbConfig} for the IC+ Oracle database; never {@code null}
     */
    public DbConfig getIcPlusDbConfig() {
        return new DbConfig(
                get("icplus.db.name",     "IC+ DB"),
                get("icplus.db.hostname", ""),
                getInt("icplus.db.port",  1521),
                get("icplus.db.sid",      ""),
                get("icplus.db.username", ""),
                get("icplus.db.password", "")
        );
    }

    /**
     * Returns a {@link DbConfig} populated with the Vision database connection
     * parameters ({@code vision.db.*} keys) from the loaded properties.
     *
     * @return a {@link DbConfig} for the Vision Oracle database; never {@code null}
     */
    public DbConfig getVisionDbConfig() {
        return new DbConfig(
                get("vision.db.name",     "Vision DB"),
                get("vision.db.hostname", ""),
                getInt("vision.db.port",  1521),
                get("vision.db.sid",      ""),
                get("vision.db.username", ""),
                get("vision.db.password", "")
        );
    }

    /**
     * Returns the SSH server hostname used for Method 3 file retrieval.
     * Defaults to {@code "pvisapp1"} if the {@code ssh.server} property is absent.
     *
     * @return the SSH server hostname; never {@code null}
     */
    /** Injects the MySQL-backed config service used to override Oracle/SSH/link settings. */
    public void setAppConfigService(AppConfigService svc) { this.appConfigService = svc; }

    /** Returns the injected {@link AppConfigService}, or {@code null} if not yet set. */
    public AppConfigService getAppConfigService() { return appConfigService; }

    public String getAuthDbHost()     { return get("mysql.db.hostname", "localhost"); }
    public int    getAuthDbPort()     { return getInt("mysql.db.port",  3306); }
    public String getAuthDbName()     { return get("mysql.db.name",     "rxpuller_auth"); }
    public String getAuthDbUsername() { return get("mysql.db.username", ""); }
    public String getAuthDbPassword() { return get("mysql.db.password", ""); }

    /**
     * Returns the AES-256 encryption key (64 hex chars) used to encrypt
     * Oracle DB passwords stored in the MySQL {@code app_config} table.
     * Always read from {@code application.properties} — never from MySQL.
     */
    public String getEncryptionKey()  { return props.getProperty("app.encryption.key", "").trim(); }

    public String getSshServer()          { return get("ssh.server",           "pvisapp1"); }
    public String getSshBusinessUsername() { return get("ssh.business.username", ""); }
    public String getSshBusinessPassword() { return get("ssh.business.password", ""); }

    /**
     * Returns the SSH port number used for Method 3 file retrieval.
     * Defaults to {@code 22} if the {@code ssh.port} property is absent or invalid.
     *
     * @return the SSH port number
     */
    public int    getSshPort()        { return getInt("ssh.port", 22); }

    /**
     * Returns the hyperlink URL for the SOP (Standard Operating Procedure) document.
     * Returns an empty string if the {@code link.sop} property is absent.
     *
     * @return the SOP link URL; never {@code null}
     */
    public String getMailServerApi() { return get("mail.server.api", ""); }
    public String getMailFrom()      { return get("mail.from", ""); }

    public String getSopLink()      { return get("link.sop",      ""); }

    /**
     * Returns the hyperlink URL used to create or reference an incident ticket.
     * Returns an empty string if the {@code link.incident} property is absent.
     *
     * @return the incident link URL; never {@code null}
     */
    public String getIncidentLink()   { return get("link.incident", ""); }

    /**
     * Sets the SSH server hostname in memory.  Call {@link #save()} to persist.
     *
     * @param v the SSH server hostname to set; must not be {@code null}
     */
    public void setSshServer(String v)    { props.setProperty("ssh.server",  v); }

    /**
     * Sets the SSH port number in memory.  Call {@link #save()} to persist.
     *
     * @param v the SSH port number to set
     */
    public void setSshPort(int v)         { props.setProperty("ssh.port",    String.valueOf(v)); }

    /**
     * Sets the SOP link URL in memory.  Call {@link #save()} to persist.
     *
     * @param v the SOP hyperlink URL to set; must not be {@code null}
     */
    public void setSopLink(String v)      { props.setProperty("link.sop",    v); }

    /**
     * Sets the incident link URL in memory.  Call {@link #save()} to persist.
     *
     * @param v the incident hyperlink URL to set; must not be {@code null}
     */
    public void setIncidentLink(String v) { props.setProperty("link.incident", v); }

    private String get(String key, String def) {
        // mysql.db.* keys always come from application.properties (never stored in MySQL)
        if (appConfigService != null && !key.startsWith("mysql.db.")) {
            String dbVal = appConfigService.get(key);
            if (dbVal != null) return dbVal;
        }
        return props.getProperty(key, def).trim();
    }

    private int getInt(String key, int def) {
        try { return Integer.parseInt(get(key, String.valueOf(def))); }
        catch (NumberFormatException e) { return def; }
    }
}

