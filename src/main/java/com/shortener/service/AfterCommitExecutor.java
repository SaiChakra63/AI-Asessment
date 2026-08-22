package com.shortener.service;

import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Objects;

@Component
public class AfterCommitExecutor {

    /**
     * Runs the action after a successful transaction commit. When no transaction
     * synchronization is active, the action runs immediately on the calling thread.
     * The action is not invoked after rollback.
     *
     * @param action non-null work that must observe committed database state
     */
    public void execute(Runnable action) {
        Objects.requireNonNull(action, "action must not be null");
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            action.run();
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                action.run();
            }
        });
    }
}
