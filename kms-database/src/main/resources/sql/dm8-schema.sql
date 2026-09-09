-- DM8 schema for KMS: collector configuration, latest metric values (Step 3),
-- and analyser tables (Step 4). Executed automatically by the collectors/
-- analysers/web SchemaInitializers on startup (idempotent); this file is the
-- canonical reference. Run manually with:
--   MSYS_NO_PATHCONV=1 docker exec -i dm8_test /opt/dmdbms/bin/disql -S SYSDBA/SYSDBA001 < dm8-schema.sql
-- Note: table names are stored uppercase in DM8 (USER_TABLES).

CREATE TABLE t_collector_config (
    id             INT          NOT NULL,
    period_seconds INT          NOT NULL,
    enabled        INT          NOT NULL,
    update_time    TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE t_metric_latest (
    metric_code   VARCHAR(32)  NOT NULL,
    metric_name   VARCHAR(64),
    current_value DOUBLE,
    unit          VARCHAR(16),
    update_time   TIMESTAMP,
    PRIMARY KEY (metric_code)
);

-- Seed data (same as SchemaInitializer): single config row + six metric rows.
INSERT INTO t_collector_config (id, period_seconds, enabled, update_time) VALUES (1, 60, 1, SYSDATE);

INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('cpu_usage', 'CPU Usage', '%');
INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('mem_usage', 'Memory Usage', '%');
INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('disk_free', 'Disk Free Space', 'GB');
INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('net_send', 'Network Send Rate', 'KB/s');
INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('net_recv', 'Network Receive Rate', 'KB/s');
INSERT INTO t_metric_latest (metric_code, metric_name, unit) VALUES ('cpu_temp', 'CPU Temperature', 'C');

-- ===== Step 4: analyser tables =====

CREATE TABLE t_metric_def (
    metric_code          VARCHAR(32) NOT NULL,
    metric_name          VARCHAR(64),
    unit                 VARCHAR(16),
    upper_threshold      DOUBLE,
    lower_threshold      DOUBLE,
    volatility_threshold DOUBLE,
    dynamic_threshold    INT,
    update_time          TIMESTAMP,
    PRIMARY KEY (metric_code)
);

CREATE TABLE t_alert_rule (
    id         INT NOT NULL,
    rule_code  VARCHAR(64),
    rule_name  VARCHAR(128),
    metric_code VARCHAR(32),
    expression VARCHAR(256),
    enabled    INT,
    update_time TIMESTAMP,
    PRIMARY KEY (id)
);

CREATE TABLE t_alert_record (
    id              BIGINT IDENTITY(1,1),
    metric_code     VARCHAR(32),
    metric_name     VARCHAR(64),
    alert_level     INT,
    alert_type      VARCHAR(32),
    message         VARCHAR(512),
    current_value   DOUBLE,
    threshold_value DOUBLE,
    volatility      DOUBLE,
    status          INT,
    create_time     TIMESTAMP,
    update_time     TIMESTAMP,
    PRIMARY KEY (id)
);

-- Seed metric definitions (upper/lower thresholds; volatility in percent;
-- dynamic_threshold 1 = recompute mean+-2*std every 5 minutes).
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('cpu_usage', 'CPU Usage', '%', 90, 0, 10, 1);
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('mem_usage', 'Memory Usage', '%', 90, 0, 10, 0);
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('disk_free', 'Disk Free Space', 'GB', NULL, 10, 10, 0);
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('net_send', 'Network Send Rate', 'KB/s', 51200, 0, 15, 0);
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('net_recv', 'Network Receive Rate', 'KB/s', 51200, 0, 15, 0);
INSERT INTO t_metric_def (metric_code, metric_name, unit, upper_threshold, lower_threshold, volatility_threshold, dynamic_threshold) VALUES ('cpu_temp', 'CPU Temperature', 'C', 90, 0, 10, 0);

-- Seed alert rules (condition expressions, variables bound from t_metric_def).
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (1, 'RULE_CPU', 'CPU usage threshold', 'cpu_usage', 'value > upper_threshold', 1);
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (2, 'RULE_MEM', 'Memory usage threshold', 'mem_usage', 'value > upper_threshold', 1);
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (3, 'RULE_DISK', 'Disk free space floor', 'disk_free', 'value < lower_threshold', 1);
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (4, 'RULE_NET_SEND', 'Network send rate ceiling', 'net_send', 'value > upper_threshold', 1);
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (5, 'RULE_NET_RECV', 'Network receive rate ceiling', 'net_recv', 'value > upper_threshold', 1);
INSERT INTO t_alert_rule (id, rule_code, rule_name, metric_code, expression, enabled) VALUES (6, 'RULE_TEMP', 'CPU temperature threshold', 'cpu_temp', 'value > upper_threshold', 1);
