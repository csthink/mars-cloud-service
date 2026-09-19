package com.mars.cloud.service.upms.interfaces.controller;

import com.mars.cloud.common.response.UnifyResponse;
import com.mars.cloud.mvc.annotation.IgnoreResponseAnnotation;
import com.mars.cloud.service.upms.infrastructure.error.DecisionResponseEncoder;
import com.mars.cloud.service.upms.infrastructure.error.UpmsProtocolCode;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.HttpMediaTypeNotSupportedException;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@Order(Ordered.HIGHEST_PRECEDENCE)
@IgnoreResponseAnnotation
@RestControllerAdvice
public class DecisionExceptionHandler {

    private final DecisionResponseEncoder encoder;

    public DecisionExceptionHandler(DecisionResponseEncoder encoder) {
        this.encoder = encoder;
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<UnifyResponse<Object>> handleUnreadableMessage(HttpMessageNotReadableException ex) {
        return encoder.error(HttpStatus.BAD_REQUEST, UpmsProtocolCode.MALFORMED_REQUEST, "malformed request");
    }

    @ExceptionHandler(HttpMediaTypeNotSupportedException.class)
    public ResponseEntity<UnifyResponse<Object>> handleUnsupportedMediaType(HttpMediaTypeNotSupportedException ex) {
        return encoder.error(HttpStatus.UNSUPPORTED_MEDIA_TYPE, UpmsProtocolCode.MALFORMED_REQUEST, "malformed request");
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<UnifyResponse<Object>> handleUnsupportedMethod(HttpRequestMethodNotSupportedException ex) {
        return encoder.error(HttpStatus.METHOD_NOT_ALLOWED, UpmsProtocolCode.MALFORMED_REQUEST, "malformed request");
    }
}
