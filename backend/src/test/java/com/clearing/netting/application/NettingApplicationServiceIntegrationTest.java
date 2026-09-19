package com.clearing.netting.application;

import com.clearing.netting.domain.exception.DomainException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression: a failed netting run must be durably persisted as FAILED
 * (status + reason) so it shows up in the run list and its detail page
 * can be opened. Previously the failure update ran in the outer transaction,
 * which rolled back when the exception propagated, leaving the run stuck at
 * RUNNING.
 */
@SpringBootTest
class NettingApplicationServiceIntegrationTest {

    @Autowired
    private NettingApplicationService service;

    @Autowired
    private ObligationRepositoryPort obligationRepository;

    @Autowired
    private MemberRepositoryPort memberRepository;

    @Test
    void failedRunIsPersistedWithFailedStatusAndReason() {
        // No OPEN obligations for this date/currency -> NO_OBLIGATIONS failure.
        LocalDate emptyDate = LocalDate.of(2030, 1, 1);

        DomainException ex = assertThrows(DomainException.class,
                () -> service.execute(emptyDate, "USD"));
        assertEquals("NO_OBLIGATIONS", ex.getCode());

        List<NettingRun> failed = service.listRuns().stream()
                .filter(r -> r.getSettleDate().equals(emptyDate))
                .toList();
        assertEquals(1, failed.size(), "failed run must appear in the list");

        NettingRun run = failed.get(0);
        assertEquals(NettingRunStatus.FAILED, run.getStatus(), "run must not be stuck at RUNNING");
        assertNotNull(run.getFailureReason());
        assertTrue(run.getFailureReason().contains("OPEN"));

        // The detail page loads the run via getRun — it must open and show the failure.
        NettingRun fetched = service.getRun(run.getRunId());
        assertEquals(NettingRunStatus.FAILED, fetched.getStatus());
        assertEquals(run.getFailureReason(), fetched.getFailureReason());
    }

    @Test
    void successfulRunStillCompletes() {
        LocalDate settleDate = LocalDate.of(2031, 2, 3);
        memberRepository.save(new Member("mem-alpha", "Alpha Bank", MemberStatus.ACTIVE));
        memberRepository.save(new Member("mem-beta", "Beta Securities", MemberStatus.ACTIVE));
        obligationRepository.save(TradeObligation.open(
                "mem-alpha", "mem-beta", "USD", new BigDecimal("100"), settleDate.minusDays(1), settleDate));
        obligationRepository.save(TradeObligation.open(
                "mem-beta", "mem-alpha", "USD", new BigDecimal("100"), settleDate.minusDays(1), settleDate));

        NettingApplicationService.NettingRunResult result = service.execute(settleDate, "USD");
        assertEquals(NettingRunStatus.COMPLETED, result.run().getStatus());
        assertEquals(2, result.positions().size());

        NettingRun fetched = service.getRun(result.run().getRunId());
        assertEquals(NettingRunStatus.COMPLETED, fetched.getStatus());
        assertNull(fetched.getFailureReason());
    }
}
