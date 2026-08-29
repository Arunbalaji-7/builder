CREATE DATABASE IF NOT EXISTS rxpuller_auth
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE rxpuller_auth;

-- ---- Users -------------------------------------------------------
CREATE TABLE IF NOT EXISTS raw_xml_data_puller_users (
    id                   CHAR(36)                        NOT NULL PRIMARY KEY,
    username             VARCHAR(100)                    NOT NULL UNIQUE,
    password_hash        VARCHAR(255)                    NOT NULL,        -- PBKDF2-SHA256 format: <iterations>:<base64-salt>:<base64-hash>
    full_name            VARCHAR(200),
    email                VARCHAR(200),
    role                 ENUM('ADMIN','USER','BUSINESS') NOT NULL DEFAULT 'USER',
    active               TINYINT(1)                      NOT NULL DEFAULT 1,
    must_change_password TINYINT(1)                      NOT NULL DEFAULT 0,
    created_at           TIMESTAMP                       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_login           TIMESTAMP                       NULL
);

-- ---- Login History -----------------------------------------------
-- Auto-pruned: entries older than 6 months are deleted on each login.
-- user_id is nullable so history rows are kept when a user account is deleted.
CREATE TABLE IF NOT EXISTS raw_xml_data_puller_login_history (
    id         CHAR(36)     NOT NULL PRIMARY KEY,
    user_id    CHAR(36)     NULL,
    username   VARCHAR(100) NOT NULL,
    full_name  VARCHAR(200),
    login_time TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP,
    FOREIGN KEY (user_id) REFERENCES raw_xml_data_puller_users(id) ON DELETE SET NULL
);

-- ---- Admin-editable app config -----------------------------------
-- Stores Oracle DB, SSH, and link settings edited via Admin Panel.
-- Keys mirror application.properties (e.g. erx.db.hostname).
-- Rows here take precedence over application.properties at runtime.
CREATE TABLE IF NOT EXISTS raw_xml_data_puller_app_config (
    config_key   VARCHAR(100) NOT NULL PRIMARY KEY,
    config_value TEXT,
    updated_at   TIMESTAMP    NOT NULL DEFAULT CURRENT_TIMESTAMP
    ON UPDATE CURRENT_TIMESTAMP
);

-- ----------------------------------------------------------------
-- Upgrade scripts for existing installations:
--
--   ALTER TABLE raw_xml_data_puller_users
--       ADD COLUMN IF NOT EXISTS email VARCHAR(200) DEFAULT NULL,
--       ADD COLUMN IF NOT EXISTS must_change_password TINYINT(1) NOT NULL DEFAULT 0;
--
--   ALTER TABLE raw_xml_data_puller_login_history
--       MODIFY COLUMN user_id CHAR(36) NULL,
--       DROP FOREIGN KEY <fk_name>,
--       ADD CONSTRAINT FOREIGN KEY (user_id) REFERENCES raw_xml_data_puller_users(id) ON DELETE SET NULL;
--
-- ----------------------------------------------------------------
-- Seed an admin user
--
-- Passwords are hashed with PBKDF2-SHA256 (310 000 iterations).
-- Use PasswordUtil to generate a hash before inserting.
--
-- Temporary seed — username: admin  /  password: Admin@1234
-- Change the password immediately after first login.
-- ----------------------------------------------------------------
-- INSERT INTO raw_xml_data_puller_users (id, username, password_hash, full_name, role)
-- VALUES (UUID(), 'admin', '310000:Z1u9Am1l9ucZWZAgq44SzQ==:CGzeL69Xub7O2B/zB7Huw3g9ATEik4bXFwebqDDOtQU=', 'Administrator', 'ADMIN');
