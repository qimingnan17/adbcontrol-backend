package com.adbcontrol.backend.data

object MysqlSchema {
    const val CREATE_ADMIN_USER = """
        CREATE TABLE IF NOT EXISTS admin_user (
            id INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
            username VARCHAR(64) NOT NULL UNIQUE,
            password_hash VARCHAR(128) NOT NULL,
            role VARCHAR(32) NOT NULL DEFAULT 'admin',
            created_at BIGINT UNSIGNED NOT NULL,
            last_login_at BIGINT UNSIGNED NOT NULL DEFAULT 0,
            password_changed_at BIGINT UNSIGNED NOT NULL DEFAULT 0,
            totp_secret VARCHAR(64) NULL
        ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """
}
