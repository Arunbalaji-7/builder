package com.walgreens.rawxmldatapuller.service;

import com.jcraft.jsch.*;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.Properties;

/**
 * JSch-based SSH client for connecting to the {@code pvisapp1} image server and
 * executing shell commands remotely.
 * <p>
 * This service is used exclusively by {@link Method3Service} to locate and read
 * raw XML image files stored on the Vision image server.  A single instance
 * represents one SSH {@link Session}; call {@link #connect(String, String)} before
 * issuing commands and {@link #close()} (or use try-with-resources) when finished.
 * </p>
 * <p>
 * The target host and port are read from {@link ConfigLoader} at connect time, so
 * changes made via the Settings dialog take effect without restarting the
 * application.
 * </p>
 *
 * <p>Implements {@link AutoCloseable} so it can be used in try-with-resources blocks.</p>
 */
public class SshService implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SshService.class);
    private static final int CONNECT_TIMEOUT_MS = 30_000;
    private static final int COMMAND_TIMEOUT_MS = 60_000;

    private Session session;

    /**
     * Establishes an SSH session using password-based authentication.
     * <p>
     * The target host and port are resolved from {@link ConfigLoader} at the time
     * this method is called.  Host-key verification is disabled
     * ({@code StrictHostKeyChecking=no}) for compatibility with internal servers.
     * The connection attempt will time out after 30 seconds.
     * </p>
     *
     * @param username the SSH login username
     * @param password the SSH login password
     * @throws JSchException if the connection or authentication fails
     */
    public void connect(String username, String password) throws JSchException {
        ConfigLoader cfg = ConfigLoader.getInstance();
        String host = cfg.getSshServer();
        int port    = cfg.getSshPort();

        log.info("SSH connect to {}@{}:{}", username, host, port);

        JSch jsch = new JSch();
        Properties config = new Properties();
        config.put("StrictHostKeyChecking",       "no");
        config.put("PreferredAuthentications",    "password");

        session = jsch.getSession(username, host, port);
        session.setPassword(password);
        session.setConfig(config);
        session.connect(CONNECT_TIMEOUT_MS);
        log.info("SSH session established");
    }

    /**
     * Executes a shell command on the remote server and returns its standard output.
     * <p>
     * The command is run via a JSch {@code exec} channel.  Standard error is
     * forwarded to {@link System#err}.  The method blocks until the channel closes
     * or the 60-second command timeout is reached.
     * </p>
     * <p>
     * Exit code {@code 1} is treated as a successful (no-match) result from
     * {@code grep} and does not cause an exception.  Any other non-zero exit code
     * is considered a failure.
     * </p>
     *
     * @param command the shell command to execute on the remote host
     * @return the full standard output of the command as a {@code String};
     *         may be empty if the command produced no output
     * @throws IllegalStateException if the SSH session is not currently connected
     * @throws Exception             if the command times out or exits with a
     *                               non-zero, non-grep exit code
     */
    public String executeCommand(String command) throws Exception {
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("SSH session is not connected");
        }
        log.info("SSH exec: {}", command);

        ChannelExec channel = (ChannelExec) session.openChannel("exec");
        channel.setCommand(command);
        channel.setErrStream(System.err);

        ByteArrayOutputStream output = new ByteArrayOutputStream();
        channel.setOutputStream(output);
        InputStream in = channel.getInputStream();
        channel.connect(CONNECT_TIMEOUT_MS);

        try {
            byte[] buf = new byte[4096];
            long deadline = System.currentTimeMillis() + COMMAND_TIMEOUT_MS;
            while (!channel.isClosed()) {
                while (in.available() > 0) {
                    int i = in.read(buf);
                    if (i > 0) output.write(buf, 0, i);
                }
                if (System.currentTimeMillis() > deadline)
                    throw new Exception("SSH command timed out after " + COMMAND_TIMEOUT_MS / 1000 + "s");
                Thread.sleep(200);
            }
            while (in.available() > 0) {
                int i = in.read(buf);
                if (i > 0) output.write(buf, 0, i);
            }
        } finally {
            channel.disconnect();   // always disconnect the exec channel
        }

        int exitCode = channel.getExitStatus();
        String result = output.toString();
        log.info("SSH exit={}, output-len={}", exitCode, result.length());
        // grep returns 1 when no match — not a failure
        if (exitCode != 0 && exitCode != 1)
            throw new Exception("SSH command failed with exit code " + exitCode);
        return result;
    }

    /**
     * Returns whether the underlying SSH session is currently active.
     *
     * @return {@code true} if the session exists and is connected; {@code false} otherwise
     */
    public boolean isConnected() { return session != null && session.isConnected(); }

    /**
     * Disconnects the active SSH session, releasing all associated resources.
     * <p>
     * Safe to call even if no session has been established or the session is
     * already disconnected; in those cases the method is a no-op.
     * </p>
     */
    @Override
    public void close() {
        if (session != null && session.isConnected()) {
            session.disconnect();
            log.info("SSH session disconnected");
        }
    }
}

