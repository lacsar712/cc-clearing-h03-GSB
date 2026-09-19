package com.clearing.netting.domain.exception;

/**
 * 轧差执行失败时抛出。携带已持久化的 FAILED 批次 runId，
 * 便于上层（错误响应/前端）直接定位失败批次详情。
 */
public class NettingRunFailedException extends DomainException {

    private final String runId;

    public NettingRunFailedException(String runId, String code, String message) {
        super(code, message);
        this.runId = runId;
    }

    public String getRunId() {
        return runId;
    }
}
