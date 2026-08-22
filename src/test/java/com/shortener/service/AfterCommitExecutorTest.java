package com.shortener.service;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AfterCommitExecutorTest {

    private final AfterCommitExecutor executor = new AfterCommitExecutor();

    @Test
    void runsImmediatelyWithoutTransactionSynchronization() {
        AtomicInteger executions = new AtomicInteger();

        executor.execute(executions::incrementAndGet);

        assertEquals(1, executions.get());
    }

    @Test
    void defersActionUntilSuccessfulCommitCallback() {
        AtomicInteger executions = new AtomicInteger();
        TransactionSynchronizationManager.initSynchronization();
        try {
            executor.execute(executions::incrementAndGet);

            assertEquals(0, executions.get());
            assertEquals(1, TransactionSynchronizationManager.getSynchronizations().size());
            TransactionSynchronizationManager.getSynchronizations().get(0).afterCommit();
            assertEquals(1, executions.get());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }
}
