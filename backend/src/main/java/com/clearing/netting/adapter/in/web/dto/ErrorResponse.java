package com.clearing.netting.adapter.in.web.dto;

import java.util.List;

public record ErrorResponse(String code, String message, List<String> details, String runId) {
    public static ErrorResponse of(String code, String message) {
        return new ErrorResponse(code, message, List.of(), null);
    }

    public static ErrorResponse of(String code, String message, List<String> details) {
        return new ErrorResponse(code, message, details == null ? List.of() : details, null);
    }

    public static ErrorResponse of(String code, String message, List<String> details, String runId) {
        return new ErrorResponse(code, message, details == null ? List.of() : details, runId);
    }
}
