package com.clamber.playback.config;

import java.io.IOException;
import com.clamber.playback.exception.ClamberException;
import com.clamber.playback.utils.CommonResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Slf4j
@RestControllerAdvice
public class ExceptionInspect {

    @ExceptionHandler(ClamberException.class)
    public CommonResult<?> handleClamberException(ClamberException e) {
        return CommonResult.error(e.getCode(), e.getMessage());
    }

    @ExceptionHandler(IOException.class)
    public CommonResult<?> handleIOException(IOException e) {
        log.error("文件操作失败:{}", e.getMessage());
        return CommonResult.error(500, "文件操作失败");
    }
}
