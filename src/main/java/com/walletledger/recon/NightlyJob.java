package com.walletledger.recon;

import com.walletledger.explain.FlagExplanationService;
import com.walletledger.idempotency.IdempotencyService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * The background worker's nightly run. Only active where wallet.worker.enabled=true
 * (the "worker" container), so API replicas never schedule it.
 *
 * <ol>
 *   <li>take the job lock (exit if another run holds it)</li>
 *   <li>reconcile the books</li>
 *   <li>explain the day's risk flags with the LLM</li>
 *   <li>purge idempotency keys older than 24 hours</li>
 * </ol>
 */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "wallet.worker.enabled", havingValue = "true")
public class NightlyJob {

    private static final Logger log = LoggerFactory.getLogger(NightlyJob.class);

    private final JobLocks jobLocks;
    private final ReconciliationService reconciliation;
    private final FlagExplanationService explanations;
    private final IdempotencyService idempotency;

    public NightlyJob(JobLocks jobLocks, ReconciliationService reconciliation, FlagExplanationService explanations,
                      IdempotencyService idempotency) {
        this.jobLocks = jobLocks;
        this.reconciliation = reconciliation;
        this.explanations = explanations;
        this.idempotency = idempotency;
    }

    @Scheduled(cron = "${wallet.reconciliation.cron}", zone = "${wallet.timezone}")
    public void run() {
        var lock = jobLocks.tryAcquire(ReconciliationService.JOB_NAME);
        if (lock.isEmpty()) {
            log.info("Nightly job skipped: another run holds the lock");
            return;
        }
        try (JobLocks.Held held = lock.get()) {
            long runId = reconciliation.createRun("SCHEDULED");
            reconciliation.runChecks(runId);
            log.info("Nightly reconciliation run {} done", runId);

            FlagExplanationService.Summary summary = explanations.explainPending(500);
            log.info("Risk flag explanations: {}", summary);

            int purged = idempotency.purgeOlderThanHours(24);
            log.info("Purged {} idempotency keys older than 24h", purged);
        }
    }
}
