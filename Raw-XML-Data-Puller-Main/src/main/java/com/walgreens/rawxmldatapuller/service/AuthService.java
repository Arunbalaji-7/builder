package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.UUID;

public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);

    public User login(String username, String password) throws AuthException {
        if (username == null || username.isBlank()) throw new AuthException("Username is required.");
        if (password == null || password.isEmpty())  throw new AuthException("Password is required.");

        ConfigLoader cfg = ConfigLoader.getInstance();
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                cfg.getAuthDbHost(), cfg.getAuthDbPort(), cfg.getAuthDbName());

        try (Connection conn = DriverManager.getConnection(url, cfg.getAuthDbUsername(), cfg.getAuthDbPassword())) {
            String sql = "SELECT id, username, password_hash, full_name, email, role, active, must_change_password " +
                         "FROM raw_xml_data_puller_users WHERE username = ?";
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                ps.setString(1, username.trim());
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next())
                        throw new AuthException("Invalid username or password.");

                    if (!rs.getBoolean("active"))
                        throw new AuthException("Your account is inactive. Contact your administrator.");

                    if (!PasswordUtil.verify(password, rs.getString("password_hash")))
                        throw new AuthException("Invalid username or password.");

                    User user = new User(
                            rs.getString("id"),
                            rs.getString("username"),
                            rs.getString("full_name"),
                            rs.getString("email"),
                            rs.getString("role"),
                            rs.getBoolean("must_change_password")
                    );
                    updateLastLogin(conn, user.getId());
                    logLoginHistory(conn, user);
                    cleanupOldHistory(conn);
                    log.info("User authenticated: {}", user.getUsername());
                    return user;
                }
            }
        } catch (AuthException e) {
            throw e;
        } catch (Exception e) {
            log.error("Authentication DB error", e);
            throw new AuthException("Unable to connect to authentication service. Contact your administrator.");
        }
    }

    private void updateLastLogin(Connection conn, String userId) {
        try (PreparedStatement ps = conn.prepareStatement(
                "UPDATE raw_xml_data_puller_users SET last_login = NOW() WHERE id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not update last_login for user id={}", userId, e);
        }
    }

    private void logLoginHistory(Connection conn, User user) {
        try (PreparedStatement ps = conn.prepareStatement(
                "INSERT INTO raw_xml_data_puller_login_history (id, user_id, username, full_name) VALUES (?, ?, ?, ?)")) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, user.getId());
            ps.setString(3, user.getUsername());
            ps.setString(4, user.getFullName());
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not log login history for user {}", user.getUsername(), e);
        }
    }

    private void cleanupOldHistory(Connection conn) {
        try (PreparedStatement ps = conn.prepareStatement(
                "DELETE FROM raw_xml_data_puller_login_history WHERE login_time < DATE_SUB(NOW(), INTERVAL 6 MONTH)")) {
            ps.executeUpdate();
        } catch (SQLException e) {
            log.warn("Could not cleanup old login history", e);
        }
    }
}
