package com.kms.collector.service;

import com.kms.collector.model.CollectedMetrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import oshi.SystemInfo;
import oshi.hardware.GlobalMemory;
import oshi.hardware.NetworkIF;

import java.io.File;
import java.time.LocalDateTime;

/**
 * OSHI-based host metric collection: CPU usage %, memory usage %, disk free
 * space (GB), network send/receive rate (KB/s) and CPU temperature (spec 4.2).
 */
@Service
public class MetricCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectorService.class);

    /** OSHI 7.x detects the platform automatically (service-provider model). */
    private final SystemInfo systemInfo = new SystemInfo();

    /** Network byte counters of the previous sample, for rate computation. */
    private long prevNetBytesSent;
    private long prevNetBytesRecv;
    private long prevNetSampleMillis;
    private boolean temperatureUnsupportedLogged;

    public CollectedMetrics collect() {
        // Blocks ~1 s while sampling CPU load — acceptable at >= 60 s periods.
        double cpuUsage = systemInfo.getHardware().getProcessor().getSystemCpuLoad(1000) * 100.0;

        GlobalMemory memory = systemInfo.getHardware().getMemory();
        double memUsage = (memory.getTotal() - memory.getAvailable()) * 100.0 / memory.getTotal();

        // Free space of the drive the collector runs from, GB.
        double diskFreeGb = new File(".").getFreeSpace() / 1024.0 / 1024.0 / 1024.0;

        double[] netKbps = sampleNetworkRates();

        Double cpuTemp = readCpuTemperature();

        return new CollectedMetrics(LocalDateTime.now(), round2(cpuUsage), round2(memUsage),
                round2(diskFreeGb), round2(netKbps[0]), round2(netKbps[1]), cpuTemp);
    }

    /**
     * Rates summed over all non-loopback, non-down interfaces, based on the
     * byte counters since the previous call. First call returns 0 (no baseline yet).
     *
     * @return [sendKbps, recvKbps]
     */
    private double[] sampleNetworkRates() {
        long sendTotal = 0;
        long recvTotal = 0;
        for (NetworkIF nif : systemInfo.getHardware().getNetworkIFs(true)) {
            nif.updateAttributes();
            String name = nif.getName();
            if (name == null || name.toLowerCase().contains("loopback") || name.equalsIgnoreCase("lo")) {
                continue;
            }
            if (nif.getIfOperStatus() == NetworkIF.IfOperStatus.DOWN) {
                continue;
            }
            sendTotal += nif.getBytesSent();
            recvTotal += nif.getBytesRecv();
        }

        long now = System.currentTimeMillis();
        double[] rates = {0.0, 0.0};
        if (prevNetSampleMillis > 0 && now > prevNetSampleMillis) {
            double seconds = (now - prevNetSampleMillis) / 1000.0;
            rates[0] = Math.max((sendTotal - prevNetBytesSent) / 1024.0 / seconds, 0);
            rates[1] = Math.max((recvTotal - prevNetBytesRecv) / 1024.0 / seconds, 0);
        }
        prevNetBytesSent = sendTotal;
        prevNetBytesRecv = recvTotal;
        prevNetSampleMillis = now;
        return rates;
    }

    /** OSHI rarely exposes CPU temperature on Windows; null means unsupported (spec allows). */
    private Double readCpuTemperature() {
        double temp = systemInfo.getHardware().getSensors().getCpuTemperature();
        if (temp <= 0 || Double.isNaN(temp)) {
            if (!temperatureUnsupportedLogged) {
                temperatureUnsupportedLogged = true;
                log.warn("CPU temperature is not available on this host; cpu_temp will be stored as null");
            }
            return null;
        }
        return round2(temp);
    }

    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
