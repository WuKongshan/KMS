package com.kms.web.dto;

import java.util.List;

/**
 * A tail of one application's log file, shaped for the log viewer page.
 *
 * <p>【中文】某个应用日志文件的一段尾部内容，供"系统日志"页面展示。
 * 之所以返回<b>尾部</b>而不是全文：日志文件可能很大，而排查问题几乎总是看最近的内容；
 * 服务端只读文件末尾若干字节，避免把整个文件读进内存。
 */
public class LogTail {

    // 服务名（白名单内：collector / analyser / web）
    private String service;
    // 实际读取到的日志文件绝对路径；未找到任何候选文件时为 null
    private String file;
    // 文件是否存在且读取成功
    private boolean available;
    // 不可用时的说明（如"未找到日志文件，已探测的目录: ..."），可用时为 null
    private String message;
    // 请求的行数（已被钳制到 1 ~ 2000）
    private int requestedLines;
    // 实际返回的行数（关键字过滤后可能少于请求行数）
    private int returnedLines;
    // 是否因为文件过大而只读取了尾部（读到的首行可能不完整）
    private boolean truncated;
    // 关键字过滤后的日志行，按文件中的原始顺序
    private List<String> lines;

    public String getService() {
        return service;
    }

    public void setService(String service) {
        this.service = service;
    }

    public String getFile() {
        return file;
    }

    public void setFile(String file) {
        this.file = file;
    }

    public boolean isAvailable() {
        return available;
    }

    public void setAvailable(boolean available) {
        this.available = available;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public int getRequestedLines() {
        return requestedLines;
    }

    public void setRequestedLines(int requestedLines) {
        this.requestedLines = requestedLines;
    }

    public int getReturnedLines() {
        return returnedLines;
    }

    public void setReturnedLines(int returnedLines) {
        this.returnedLines = returnedLines;
    }

    public boolean isTruncated() {
        return truncated;
    }

    public void setTruncated(boolean truncated) {
        this.truncated = truncated;
    }

    public List<String> getLines() {
        return lines;
    }

    public void setLines(List<String> lines) {
        this.lines = lines;
    }
}
