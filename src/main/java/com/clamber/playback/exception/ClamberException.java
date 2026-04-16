package com.clamber.playback.exception;

public class ClamberException extends RuntimeException {
    private final int code;

    public ClamberException(String message) {
        super(message);
        this.code = 31;
    }

    public ClamberException(int code, String message) {
        super(message);
        this.code = code;
    }

    public int getCode() {
        return code;
    }
}
