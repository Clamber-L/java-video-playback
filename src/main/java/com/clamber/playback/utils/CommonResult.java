//
// Source code recreated from a .class file by IntelliJ IDEA
// (powered by FernFlower decompiler)
//

package com.clamber.playback.utils;

import java.io.Serializable;

public class CommonResult<T> implements Serializable {
    private static final long serialVersionUID = 3L;
    public static final String DEF_ERROR_MESSAGE = "系统繁忙";
    public static final int SUCCESS_CODE = 30;
    public static final int ERROR_CODE = 31;
    public static final int NOTFOUND_CODE = 44;
    private Boolean result;
    private int code;
    private T data;
    private String message = "ok";
    private String traceId;
    private Long timestamp;

    private CommonResult() {
        this.timestamp = System.currentTimeMillis();
    }

    public CommonResult(boolean result, int code, T data, String msg) {
        this.result = result;
        this.code = code;
        this.data = data;
        this.message = msg;
        this.timestamp = System.currentTimeMillis();
    }

    public static <T> CommonResult<T> result(boolean result, int code, T data, String msg) {
        return new CommonResult(result, code, data, msg);
    }

    public static <T> CommonResult<T> success() {
        return new CommonResult(true, 30, (Object)null, "操作成功");
    }

    public static <T> CommonResult<T> success(T data) {
        return new CommonResult<>(true, 200, data, "操作成功");
    }

    public static <T> CommonResult<T> success(T data, String msg) {
        return new CommonResult(true, 30, data, msg);
    }

    public static <T> CommonResult<T> success(int code, T data, String msg) {
        return new CommonResult(true, code, data, msg);
    }

    public static <T> CommonResult<T> success(boolean result, int code, T data, String msg) {
        return new CommonResult(result, code, data, msg);
    }

    public static <T> CommonResult<T> empty() {
        return new CommonResult(true, 44, (Object)null, "无数据");
    }

    public static <T> CommonResult<T> empty(T data, String msg) {
        return new CommonResult(true, 44, data, msg);
    }

    public static <T> CommonResult<T> empty(int code, T data, String msg) {
        return new CommonResult(true, code, data, msg);
    }

    public static <T> CommonResult<T> empty(boolean result, int code, T data, String msg) {
        return new CommonResult(result, code, data, msg);
    }

    public static <T> CommonResult<T> error() {
        return new CommonResult(false, 31, (Object)null, "操作失败");
    }

    public static <T> CommonResult<T> error(String msg) {
        return new CommonResult(false, 31, (Object)null, msg);
    }

    public static <T> CommonResult<T> error(T data, String msg) {
        return new CommonResult(false, 31, data, msg);
    }

    public static <T> CommonResult<T> error(int code, String msg) {
        return new CommonResult(false, code, (Object)null, msg);
    }

    public static <T> CommonResult<T> error(int code, T data, String msg) {
        return new CommonResult(false, code, data, msg);
    }

    public static <T> CommonResult<T> error(boolean result, int code, T data, String msg) {
        return new CommonResult(result, code, data, msg);
    }

    public String toString() {
        return "CommonResult(result=" + this.getResult() + ", code=" + this.getCode() + ", data=" + this.getData() + ", message=" + this.getMessage() + ", traceId=" + this.getTraceId() + ", timestamp=" + this.getTimestamp() + ")";
    }

    public Boolean getResult() {
        return this.result;
    }

    public int getCode() {
        return this.code;
    }

    public T getData() {
        return this.data;
    }

    public String getMessage() {
        return this.message;
    }

    public String getTraceId() {
        return this.traceId;
    }

    public Long getTimestamp() {
        return this.timestamp;
    }

    public CommonResult<T> setResult(final Boolean result) {
        this.result = result;
        return this;
    }

    public CommonResult<T> setCode(final int code) {
        this.code = code;
        return this;
    }

    public CommonResult<T> setData(final T data) {
        this.data = data;
        return this;
    }

    public CommonResult<T> setMessage(final String message) {
        this.message = message;
        return this;
    }

    public CommonResult<T> setTraceId(final String traceId) {
        this.traceId = traceId;
        return this;
    }

    public CommonResult<T> setTimestamp(final Long timestamp) {
        this.timestamp = timestamp;
        return this;
    }
}
