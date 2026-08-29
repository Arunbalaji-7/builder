package com.walgreens.rawxmldatapuller.model;

/**
 * Immutable-ish value object holding the connection parameters for a single
 * Oracle database instance.
 *
 * <p>Instances are constructed by {@link com.walgreens.rawxmlpuller.util.ConfigLoader}
 * and passed to {@link com.walgreens.rawxmlpuller.service.DatabaseService}.</p>
 *
 * <h3>JDBC URL format (SID-based)</h3>
 * <pre>
 *   jdbc:oracle:thin:@{hostname}:{port}:{sid}
 * </pre>
 */
public class DbConfig {

    private String name;
    private String hostname;
    private int    port;
    private String sid;
    private String username;
    private String password;

    /** No-arg constructor for frameworks. */
    public DbConfig() {}

    /**
     * Full constructor.
     *
     * @param name     display name used in logs and the UI (e.g. {@code "eRx DB"})
     * @param hostname Oracle listener hostname or IP
     * @param port     Oracle listener port (default {@code 1521})
     * @param sid      Oracle System Identifier
     * @param username schema username
     * @param password schema password
     */
    public DbConfig(String name, String hostname, int port,
                    String sid, String username, String password) {
        this.name     = name;
        this.hostname = hostname;
        this.port     = port;
        this.sid      = sid;
        this.username = username;
        this.password = password;
    }

    /**
     * Builds the Oracle thin JDBC URL using the SID naming format.
     *
     * @return a URL string such as {@code jdbc:oracle:thin:@host:1521:MYSID}
     */
    public String getJdbcUrl() {
        return "jdbc:oracle:thin:@" + hostname + ":" + port + ":" + sid;
    }

    public String getName()           { return name; }
    public void   setName(String v)   { this.name = v; }

    public String getHostname()       { return hostname; }
    public void   setHostname(String v) { this.hostname = v; }

    public int    getPort()           { return port; }
    public void   setPort(int v)      { this.port = v; }

    public String getSid()            { return sid; }
    public void   setSid(String v)    { this.sid = v; }

    public String getUsername()       { return username; }
    public void   setUsername(String v) { this.username = v; }

    public String getPassword()       { return password; }
    public void   setPassword(String v) { this.password = v; }

    /**
     * Returns a human-readable summary (never includes the password).
     *
     * @return e.g. {@code "eRx DB (erx-host:1521/ERXSID)"}
     */
    @Override
    public String toString() {
        return name + " (" + hostname + ":" + port + "/" + sid + ")";
    }
}

