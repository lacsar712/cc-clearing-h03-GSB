package com.clearing.netting.application;

import com.clearing.netting.domain.exception.NettingRunFailedException;
import com.clearing.netting.domain.model.Member;
import com.clearing.netting.domain.model.MemberStatus;
import com.clearing.netting.domain.model.NettingRun;
import com.clearing.netting.domain.model.NettingRunStatus;
import com.clearing.netting.domain.model.ObligationStatus;
import com.clearing.netting.domain.model.TradeObligation;
import com.clearing.netting.domain.port.out.MemberRepositoryPort;
import com.clearing.netting.domain.port.out.NettingRunRepositoryPort;
import com.clearing.netting.domain.port.out.ObligationRepositoryPort;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 轧差失败持久化集成测试：覆盖 execute() 主事务回滚场景，
 * 验证 FAILED 终态在 REQUIRES_NEW 独立事务中提交后，批次可被列表/详情查询到。
 */
@SpringBootTest
class NettingFailurePersistenceIntegrationTest {

    @Autowired
    private NettingApplicationService nettingService;

    @Autowired
    private NettingRunRepositoryPort runRepository;

    @Autowired
    private ObligationRepositoryPort obligationRepository;

    @Autowired
    private MemberRepositoryPort memberRepository;

    @Test
    void failedNettingIsPersistedAndFindableInListAndDetail() {
        LocalDate settleDate = LocalDate.of(2026, 9, 19);

        Member a = memberRepository.save(new Member("M-A", "Bank A", MemberStatus.ACTIVE));
        Member b = memberRepository.save(new Member("M-B", "Bank B", MemberStatus.SUSPENDED));
        TradeObligation obligation = obligationRepository.save(TradeObligation.open(
                a.getMemberId(), b.getMemberId(), "USD", new BigDecimal("100"),
                settleDate.minusDays(1), settleDate));

        NettingRunFailedException ex = assertThrows(
                NettingRunFailedException.class,
                () -> nettingService.execute(settleDate, "USD"));
        assertEquals("SUSPENDED_MEMBER", ex.getCode());
        String failedRunId = ex.getRunId();
        assertNotNull(failedRunId);

        // 详情查询：失败批次必须能按 ID 打开，状态为 FAILED 且带失败原因
        NettingRun fetched = nettingService.getRun(failedRunId);
        assertEquals(NettingRunStatus.FAILED, fetched.getStatus());
        assertNotNull(fetched.getFailureReason());
        assertTrue(fetched.getFailureReason().contains("M-B"));

        // 列表查询：失败批次必须出现在历史批次列表中
        List<NettingRun> runs = nettingService.listRuns();
        Optional<NettingRun> inList = runs.stream().filter(r -> failedRunId.equals(r.getRunId())).findFirst();
        assertTrue(inList.isPresent(), "FAILED run must be present in run list");
        assertEquals(NettingRunStatus.FAILED, inList.get().getStatus());
        assertNotNull(inList.get().getFailureReason());

        // 主事务已回滚：义务不应被标记为 NETTED，也不关联失败批次
        TradeObligation reloaded = obligationRepository.findById(obligation.getObligationId()).orElseThrow();
        assertEquals(ObligationStatus.OPEN, reloaded.getStatus());
        assertEquals(null, reloaded.getNettingRunId());
    }

    @Test
    void domainFailureIsAlsoPersistedAsFailed() {
        LocalDate settleDate = LocalDate.of(2026, 9, 20);

        // 无 OPEN 义务 -> 领域层抛 NO_OBLIGATIONS（DomainException 路径），同样需持久化失败批次
        NettingRunFailedException ex = assertThrows(
                NettingRunFailedException.class,
                () -> nettingService.execute(settleDate, "EUR"));
        assertEquals("NO_OBLIGATIONS", ex.getCode());

        NettingRun fetched = nettingService.getRun(ex.getRunId());
        assertEquals(NettingRunStatus.FAILED, fetched.getStatus());
        assertEquals("EUR", fetched.getCurrency());
        assertNotNull(fetched.getFailureReason());
    }
}
