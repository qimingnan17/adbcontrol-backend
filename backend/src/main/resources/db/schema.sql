-- ============================================================================
-- AdbControlApp 远程 MySQL DDL(README 第七章 7.2)
-- 启动时由 DatabaseService 以 CREATE TABLE IF NOT EXISTS 幂等执行。
-- 幂等设计参见 README 7.2.1:execution_log / app_usage_daily /
-- app_activity_log / notification_log 用 UNIQUE KEY 拦截 QoS 1 重发导致的重复入库。
-- ============================================================================

-- 设备台账
CREATE TABLE IF NOT EXISTS device (
  device_id    VARCHAR(64)  PRIMARY KEY,
  name         VARCHAR(128),
  appid        VARCHAR(64)  NOT NULL,
  first_seen   BIGINT       NOT NULL,
  last_seen    BIGINT       NOT NULL,
  status       VARCHAR(16)  NOT NULL
);

-- 设备实时状态(在 device 基础上扩展遥测)
CREATE TABLE IF NOT EXISTS device_status (
  device_id        VARCHAR(64)  PRIMARY KEY,
  online           BOOLEAN      NOT NULL,
  battery          INT,
  charging         BOOLEAN,
  network          VARCHAR(16),
  network_strength INT,
  screen_on        BOOLEAN,
  foreground_pkg   VARCHAR(128),
  shizuku          BOOLEAN,
  root             BOOLEAN,
  accessibility    BOOLEAN,
  device_admin     BOOLEAN,
  android_version  VARCHAR(16),
  app_version      VARCHAR(32),
  last_seen        BIGINT       NOT NULL
);

-- 用户(多用户场景)
CREATE TABLE IF NOT EXISTS user (
  user_id     VARCHAR(64)  PRIMARY KEY,
  device_id   VARCHAR(64)  NOT NULL,
  name        VARCHAR(128),
  avatar      VARCHAR(256),
  first_seen  BIGINT       NOT NULL,
  INDEX idx_dev_user (device_id, user_id)
);

-- 任务
CREATE TABLE IF NOT EXISTS task (
  task_id      BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id    VARCHAR(64)  NOT NULL,
  rule_type    VARCHAR(32)  NOT NULL,
  cron_expr    VARCHAR(64),
  command_json TEXT         NOT NULL,
  enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
  created_at   BIGINT       NOT NULL
);

-- 命令执行日志(msg_id 幂等,防 QoS 1 重发导致重复入库)
CREATE TABLE IF NOT EXISTS execution_log (
  id           BIGINT       PRIMARY KEY AUTO_INCREMENT,
  task_id      BIGINT       NULL,
  device_id    VARCHAR(64)  NOT NULL,
  msg_id       VARCHAR(64)  NOT NULL,
  success      BOOLEAN      NOT NULL,
  output       MEDIUMTEXT,
  duration_ms  INT          NOT NULL,
  executed_at  BIGINT       NOT NULL,
  UNIQUE KEY uq_msg_id (msg_id),
  INDEX idx_dev_time (device_id, executed_at)
);

-- 应用清单
CREATE TABLE IF NOT EXISTS app (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  pkg         VARCHAR(128) NOT NULL,
  label       VARCHAR(128),
  is_blocked  BOOLEAN      NOT NULL DEFAULT FALSE,
  UNIQUE KEY uq_dev_pkg (device_id, pkg)
);

-- 应用每日使用时长(每用户每应用每日聚合,UNIQUE 复合键保证 upsert 不重复)
CREATE TABLE IF NOT EXISTS app_usage_daily (
  id              BIGINT   PRIMARY KEY AUTO_INCREMENT,
  device_id       VARCHAR(64)  NOT NULL,
  user_id         VARCHAR(64)  NOT NULL,
  pkg             VARCHAR(128) NOT NULL,
  usage_minutes   INT          NOT NULL,
  date            DATE         NOT NULL,
  uploaded_at     BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_user_pkg_date (device_id, user_id, pkg, date),
  INDEX idx_user_date (user_id, date),
  INDEX idx_pkg_date (pkg, date)
);

-- 位置历史(高频写入,接受重复,QoS 0 不要求幂等)
CREATE TABLE IF NOT EXISTS location_history (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  lat         DOUBLE       NOT NULL,
  lng         DOUBLE       NOT NULL,
  accuracy    FLOAT,
  speed       FLOAT,
  provider    VARCHAR(16)  NOT NULL,
  fence_event VARCHAR(32),
  reported_at BIGINT       NOT NULL,
  INDEX idx_dev_time (device_id, reported_at)
);

-- 应用行为日志(用 device_id + pkg + occurred_at 三元组幂等)
CREATE TABLE IF NOT EXISTS app_activity_log (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  event       VARCHAR(32)  NOT NULL,
  pkg         VARCHAR(128) NOT NULL,
  app_name    VARCHAR(128),
  duration_ms BIGINT,
  occurred_at BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_pkg_event_time (device_id, pkg, event, occurred_at),
  INDEX idx_dev_pkg_time (device_id, pkg, occurred_at),
  INDEX idx_user_time (user_id, occurred_at)
);

-- 通知日志(用 device_id + pkg + posted_at 三元组幂等)
CREATE TABLE IF NOT EXISTS notification_log (
  id          BIGINT       PRIMARY KEY AUTO_INCREMENT,
  device_id   VARCHAR(64)  NOT NULL,
  user_id     VARCHAR(64),
  pkg         VARCHAR(128) NOT NULL,
  title       VARCHAR(256),
  text        TEXT,
  posted_at   BIGINT       NOT NULL,
  UNIQUE KEY uq_dev_pkg_posted (device_id, pkg, posted_at),
  INDEX idx_dev_pkg_time (device_id, pkg, posted_at),
  INDEX idx_user_time (user_id, posted_at)
);

-- Web 管理控制台管理员账号
CREATE TABLE IF NOT EXISTS admin_user (
  id            INT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
  username      VARCHAR(64)  NOT NULL UNIQUE,
  password_hash VARCHAR(128) NOT NULL,
  role          VARCHAR(32)  NOT NULL DEFAULT 'admin',
  created_at    BIGINT UNSIGNED NOT NULL,
  last_login_at BIGINT UNSIGNED NOT NULL DEFAULT 0,
  totp_secret   VARCHAR(64) NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 配对会话(设备临时 MQTT 凭证 + 长期 sessionKey)
-- 后端重启后由此恢复完整的配对关系,避免已配对设备被迫重新扫码 pairing。
-- 安全注意:session_key / mqtt_password 属于敏感凭证,后续如需更强保护可加密钥包装(wrap)
CREATE TABLE IF NOT EXISTS pair_session (
  device_id     VARCHAR(64)  PRIMARY KEY,
  pair_token    VARCHAR(64)  NOT NULL,
  session_key   VARCHAR(128) NOT NULL,
  mqtt_password VARCHAR(128) NOT NULL,
  expires_at    BIGINT       NOT NULL,
  created_at    BIGINT       NOT NULL,
  updated_at    BIGINT       NOT NULL
);
