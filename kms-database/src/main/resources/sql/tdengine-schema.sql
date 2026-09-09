-- TDengine schema for KMS (Step 3): real-time performance time series.
-- Executed automatically by the collector's SchemaInitializer on startup (idempotent);
-- this file is the canonical reference. Run manually with:
--   docker exec -i tdengine taos -uroot -ptaosdata < tdengine-schema.sql

CREATE DATABASE IF NOT EXISTS kms;
USE kms;

-- One supertable t_perf_realtime; one subtable per monitored host (created at
-- startup, named t_perf_<sanitized-hostname>). All metrics in one row so the
-- web module's real-time charts can query a single series set.
CREATE STABLE IF NOT EXISTS t_perf_realtime (
    ts        TIMESTAMP,
    cpu_usage DOUBLE,   -- CPU usage percent
    mem_usage DOUBLE,   -- memory usage percent
    disk_free DOUBLE,   -- disk free space, GB
    net_send  DOUBLE,   -- network send rate, KB/s
    net_recv  DOUBLE,   -- network receive rate, KB/s
    cpu_temp  DOUBLE    -- CPU temperature, degrees C (null when unsupported)
) TAGS (host_name VARCHAR(64));  -- "host" is a TDengine keyword, hence host_name
