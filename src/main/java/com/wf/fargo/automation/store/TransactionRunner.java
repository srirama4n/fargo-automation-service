package com.wf.fargo.automation.store;

import com.mongodb.MongoException;
import java.util.function.Supplier;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

@Component
public class TransactionRunner {

    static final int MAX_ATTEMPTS = 3;

    private final TransactionTemplate transactions;

    public TransactionRunner(MongoTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(transactionManager);
    }

    public <T> T inTransaction(Supplier<T> work) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return transactions.execute(status -> work.get());
            } catch (RuntimeException ex) {
                last = ex;
                if (attempt == MAX_ATTEMPTS || !retryable(ex)) {
                    throw ex;
                }
            }
        }
        throw last;
    }

    private static boolean retryable(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof MongoException mongo
                    && (mongo.hasErrorLabel("TransientTransactionError")
                            || mongo.hasErrorLabel("UnknownTransactionCommitResult"))) {
                return true;
            }
            Throwable cause = current.getCause();
            current = cause == current ? null : cause;
        }
        return false;
    }
}
