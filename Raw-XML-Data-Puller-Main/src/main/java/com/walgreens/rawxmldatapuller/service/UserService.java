package com.walgreens.rawxmldatapuller.service;

import com.walgreens.rawxmldatapuller.model.User;
import com.walgreens.rawxmldatapuller.util.ConfigLoader;
import com.walgreens.rawxmldatapuller.util.PasswordUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class UserService {

    private static final Logger log            = LoggerFactory.getLogger(UserService.class);
    private static final int    MIN_PWD_LENGTH = 8;

    // ----------------------------------------------------------------
    //  Login history entry (used by admin login history view)
    // ----------------------------------------------------------------

    public static class LoginHistoryEntry {
        private final String username;
        private final String fullName;
        private final String loginTime;

        public LoginHistoryEntry(String username, String fullName, String loginTime) {
            this.username  = username;
            this.fullName  = fullName;
            this.loginTime = loginTime;
        }

        public String getUsername()  { return username; }
        public String getFullName()  { return fullName != null && !fullName.isBlank() ? fullName : username; }
        public String getLoginTime() { return loginTime != null ? loginTime : "—"; }
    }

    // ----------------------------------------------------------------
    //  DB connectivity
    // ----------------------------------------------------------------

    private Connection getConn() throws SQLException {
        ConfigLoader cfg = ConfigLoader.getInstance();
        String url = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true",
                cfg.getAuthDbHost(), cfg.getAuthDbPort(), cfg.getAuthDbName());
        return DriverManager.getConnection(url, cfg.getAuthDbUsername(), cfg.getAuthDbPassword());
    }

    // ----------------------------------------------------------------
    //  User CRUD
    // ----------------------------------------------------------------

    public List<User> getAllUsers() throws SQLException {
        List<User> list = new ArrayList<>();
        String sql = "SELECT id, username, full_name, email, role, active, must_change_password, "
                + "DATE_FORMAT(last_login, '%d-%b-%Y %H:%i') AS last_login "
                + "FROM raw_xml_data_puller_users ORDER BY username";
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new User(
                        rs.getString("id"),
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("email"),
                        rs.getString("role"),
                        rs.getBoolean("active"),
                        rs.getBoolean("must_change_password"),
                        rs.getString("last_login")
                ));
            }
        }
        return list;
    }

    /**
     * Creates a new user with an auto-generated temporary password.
     * The user will be required to change it on first login.
     *
     * @return the generated plaintext temp password (to be emailed to the user)
     */
    public String createUser(String username, String fullName, String email, String role)
            throws Exception {
        validateFields(username, role);
        String tempPassword = PasswordUtil.generateTempPassword();
        String hash         = PasswordUtil.hash(tempPassword);
        String sql = "INSERT INTO raw_xml_data_puller_users "
                   + "(id, username, password_hash, full_name, email, role, must_change_password) "
                   + "VALUES (?, ?, ?, ?, ?, ?, 1)";
        try (Connection conn = getConn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, UUID.randomUUID().toString());
            ps.setString(2, username.trim().toLowerCase());
            ps.setString(3, hash);
            ps.setString(4, fullName != null ? fullName.trim() : "");
            ps.setString(5, email    != null ? email.trim()    : "");
            ps.setString(6, role);
            ps.executeUpdate();
            log.info("User created: {}", username);
        } catch (SQLIntegrityConstraintViolationException e) {
            throw new IllegalArgumentException("Username '" + username.trim() + "' already exists.");
        }
        return tempPassword;
    }

    public void updateUser(String id, String fullName, String email, String role, boolean active)
            throws SQLException {
        String sql = "UPDATE raw_xml_data_puller_users "
                   + "SET full_name = ?, email = ?, role = ?, active = ? WHERE id = ?";
        try (Connection conn = getConn(); PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, fullName != null ? fullName.trim() : "");
            ps.setString(2, email    != null ? email.trim()    : "");
            ps.setString(3, role);
            ps.setBoolean(4, active);
            ps.setString(5, id);
            ps.executeUpdate();
        }
    }

    public void deleteUser(String id) throws SQLException {
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(
                     "DELETE FROM raw_xml_data_puller_users WHERE id = ?")) {
            ps.setString(1, id);
            ps.executeUpdate();
            log.info("User id={} deleted", id);
        }
    }

    // ----------------------------------------------------------------
    //  Password management
    // ----------------------------------------------------------------

    /**
     * Admin-triggered password reset. Auto-generates a new temp password,
     * sets must_change_password = 1, and returns the plaintext temp password
     * so it can be emailed to the user.
     */
    public String adminResetPassword(String userId) throws Exception {
        String tempPassword = PasswordUtil.generateTempPassword();
        String hash         = PasswordUtil.hash(tempPassword);
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE raw_xml_data_puller_users SET password_hash = ?, must_change_password = 1 WHERE id = ?")) {
            ps.setString(1, hash);
            ps.setString(2, userId);
            ps.executeUpdate();
            log.info("Password reset for user id={}", userId);
        }
        return tempPassword;
    }

    /** Clears the must_change_password flag after the user sets a new password. */
    public void clearMustChangePassword(String userId) throws SQLException {
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(
                     "UPDATE raw_xml_data_puller_users SET must_change_password = 0 WHERE id = ?")) {
            ps.setString(1, userId);
            ps.executeUpdate();
        }
    }

    public void changeOwnPassword(String userId, String currentPassword, String newPassword)
            throws Exception {
        if (newPassword == null || newPassword.length() < MIN_PWD_LENGTH)
            throw new IllegalArgumentException("New password must be at least " + MIN_PWD_LENGTH + " characters.");

        try (Connection conn = getConn()) {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT password_hash FROM raw_xml_data_puller_users WHERE id = ?")) {
                ps.setString(1, userId);
                try (ResultSet rs = ps.executeQuery()) {
                    if (!rs.next()) throw new AuthException("User not found.");
                    if (!PasswordUtil.verify(currentPassword, rs.getString("password_hash")))
                        throw new AuthException("Current password is incorrect.");
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "UPDATE raw_xml_data_puller_users SET password_hash = ?, must_change_password = 0 WHERE id = ?")) {
                ps.setString(1, PasswordUtil.hash(newPassword));
                ps.setString(2, userId);
                ps.executeUpdate();
                log.info("Password changed for user id={}", userId);
            }
        }
    }

    // ----------------------------------------------------------------
    //  Login history
    // ----------------------------------------------------------------

    public List<LoginHistoryEntry> getLoginHistory() throws SQLException {
        List<LoginHistoryEntry> list = new ArrayList<>();
        String sql = "SELECT username, full_name, "
                   + "DATE_FORMAT(login_time, '%d-%b-%Y %H:%i:%s') AS login_time "
                   + "FROM raw_xml_data_puller_login_history "
                   + "ORDER BY login_time DESC LIMIT 1000";
        try (Connection conn = getConn();
             PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new LoginHistoryEntry(
                        rs.getString("username"),
                        rs.getString("full_name"),
                        rs.getString("login_time")
                ));
            }
        }
        return list;
    }

    // ----------------------------------------------------------------
    //  Validation
    // ----------------------------------------------------------------

    private void validateFields(String username, String role) {
        if (username == null || username.isBlank())
            throw new IllegalArgumentException("Username is required.");
        if (!"ADMIN".equals(role) && !"USER".equals(role) && !"BUSINESS".equals(role))
            throw new IllegalArgumentException("Role must be ADMIN, USER, or BUSINESS.");
    }
}
