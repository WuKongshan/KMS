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
 *
 * <p>【中文】基于 OSHI 的宿主机指标采集：CPU 使用率（%）、内存使用率（%）、
 * 磁盘剩余空间（GB）、网络发送/接收速率（KB/s）与 CPU 温度（规格 4.2）。
 * 该服务必须运行在宿主机上，OSHI 才能读到真实硬件数据。
 */
@Service
public class MetricCollectorService {

    private static final Logger log = LoggerFactory.getLogger(MetricCollectorService.class);

    /**
     * OSHI 7.x detects the platform automatically (service-provider model).
     *
     * <p>【中文】OSHI 7.x 通过 ServiceProvider 机制自动识别操作系统。必须复用同一个
     * SystemInfo 实例：OSHI 内部会缓存 WMI / 注册表等系统句柄，若每轮采集都 new 一个，
     * 句柄会不断累积直至泄漏，因此它是字段而不是 collect() 的局部变量。
     */
    private final SystemInfo systemInfo = new SystemInfo();

    /**
     * Network byte counters of the previous sample, for rate computation.
     *
     * <p>【中文】上一次采样时网卡的累计发送/接收字节数，仅用于差分计算速率。
     */
    // 网卡字节计数器是自开机以来的累计值，必须保存上一轮读数才能算出区间速率
    private long prevNetBytesSent;
    private long prevNetBytesRecv;
    // 上一轮采样的时间戳（毫秒），把字节差分换算成“每秒”时需要它作分母
    private long prevNetSampleMillis;
    // 温度不支持的告警是否已打印过：null 是合法返回值，只需警告一次以免日志刷屏
    private boolean temperatureUnsupportedLogged;

    // 采集一个周期的全部指标：CPU、内存、磁盘、网络、温度，任一指标缺失都不会中断其它指标
    public CollectedMetrics collect() {
        // Blocks ~1 s while sampling CPU load — acceptable at >= 60 s periods.
        // 该调用会阻塞约 1 秒（OSHI 需间隔两次读数求差来算负载），周期 ≥ 60 秒时完全可以接受
        double cpuUsage = systemInfo.getHardware().getProcessor().getSystemCpuLoad(1000) * 100.0;

        GlobalMemory memory = systemInfo.getHardware().getMemory();
        // 已用量 = 总量 − 可用量；这里必须用 available 而不是 free，后者的可回收缓存会被误算成占用
        double memUsage = (memory.getTotal() - memory.getAvailable()) * 100.0 / memory.getTotal();

        // Free space of the drive the collector runs from, GB.
        // 取采集器工作目录所在磁盘的剩余空间，按 1024 进制由字节逐级换算为 GB
        double diskFreeGb = new File(".").getFreeSpace() / 1024.0 / 1024.0 / 1024.0;

        // 网络速率无法直接读取，只能靠累计计数器做差分（见 sampleNetworkRates）
        double[] netKbps = sampleNetworkRates();

        // 温度可能返回 null，表示宿主不支持，不应视为异常
        Double cpuTemp = readCpuTemperature();

        // 时间戳由本机生成；所有数值统一保留两位小数后返回
        return new CollectedMetrics(LocalDateTime.now(), round2(cpuUsage), round2(memUsage),
                round2(diskFreeGb), round2(netKbps[0]), round2(netKbps[1]), cpuTemp);
    }

    /**
     * Rates summed over all non-loopback, non-down interfaces, based on the
     * byte counters since the previous call. First call returns 0 (no baseline yet).
     *
     * <p>【中文】对所有“非回环、非 down”状态的网卡求和，用相对上一次调用的字节计数器
     * 增量计算速率；首次调用没有基线，返回 0。
     *
     * @return [sendKbps, recvKbps]
     */
    private double[] sampleNetworkRates() {
        long sendTotal = 0;
        long recvTotal = 0;
        // 入参 true 表示包含未启用/虚拟网卡，所以下面必须按名称与链路状态再过滤一遍
        for (NetworkIF nif : systemInfo.getHardware().getNetworkIFs(true)) {
            // 必须先刷新属性，否则读到的是对象创建时的旧计数
            nif.updateAttributes();
            String name = nif.getName();
            // 排除回环网卡（loopback / lo），否则本机进程间通信会被算进网络速率
            if (name == null || name.toLowerCase().contains("loopback") || name.equalsIgnoreCase("lo")) {
                continue;
            }
            // 排除已断开的网卡：其残留计数会污染速率计算
            if (nif.getIfOperStatus() == NetworkIF.IfOperStatus.DOWN) {
                continue;
            }
            sendTotal += nif.getBytesSent();
            recvTotal += nif.getBytesRecv();
        }

        long now = System.currentTimeMillis();
        double[] rates = {0.0, 0.0};
        // prevNetSampleMillis 为 0 表示首次调用，没有基线，只能返回 0 速率
        if (prevNetSampleMillis > 0 && now > prevNetSampleMillis) {
            // 用真实经过的秒数作分母，而不是配置周期，避免采样耗时带来系统性偏差
            double seconds = (now - prevNetSampleMillis) / 1000.0;
            // 字节 → KB 再除以秒数；Math.max(..., 0) 是因为驱动重载/计数器回绕时差值可能为负
            rates[0] = Math.max((sendTotal - prevNetBytesSent) / 1024.0 / seconds, 0);
            rates[1] = Math.max((recvTotal - prevNetBytesRecv) / 1024.0 / seconds, 0);
        }
        // 无论本轮是否算出速率，都要记录当前读数作为下一轮的差分基线
        prevNetBytesSent = sendTotal;
        prevNetBytesRecv = recvTotal;
        prevNetSampleMillis = now;
        return rates;
    }

    /**
     * OSHI rarely exposes CPU temperature on Windows; null means unsupported (spec allows).
     *
     * <p>【中文】Windows 上 OSHI 通常读不到 CPU 温度；返回 null 表示“不支持”，
     * 这是规格允许的合法取值。只打印一次警告，避免每个采集周期都刷日志。
     */
    private Double readCpuTemperature() {
        // OSHI 读不到温度时返回 0 或 NaN，而不是抛异常
        double temp = systemInfo.getHardware().getSensors().getCpuTemperature();
        // <= 0 与 NaN 都按“宿主不支持”处理
        if (temp <= 0 || Double.isNaN(temp)) {
            // 只在第一次记录警告，后续静默降级为 null
            if (!temperatureUnsupportedLogged) {
                temperatureUnsupportedLogged = true;
                log.warn("CPU temperature is not available on this host; cpu_temp will be stored as null");
            }
            return null;
        }
        // 读到有效温度，同样统一保留两位小数
        return round2(temp);
    }

    // 保留两位小数：先乘 100 四舍五入再除回，避免浮点数出现过多小数位
    private static double round2(double value) {
        return Math.round(value * 100.0) / 100.0;
    }
}
