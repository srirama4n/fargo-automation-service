package com.wf.fargo.automation.outbox;

import com.mongodb.client.MongoCollection;
import com.mongodb.client.MongoCursor;
import com.mongodb.client.model.Aggregates;
import com.mongodb.client.model.Filters;
import com.mongodb.client.model.changestream.ChangeStreamDocument;
import com.wf.fargo.automation.config.AppProperties;
import com.wf.fargo.automation.domain.OutboxMessage;
import com.wf.fargo.automation.observe.AutomationMetrics;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import org.bson.BsonTimestamp;
import org.bson.Document;
import org.bson.conversions.Bson;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.SmartLifecycle;
import org.springframework.core.env.Environment;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Component;

@Component
public class OutboxRelay implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelay.class);
    private static final String PENDING = "pending";
    private static final String SENT = "sent";
    private static final String FAILED = "failed";
    private static final String COLLECTION = "outbox";

    private final MongoTemplate mongoTemplate;
    private final Clock clock;
    private final AppProperties.Outbox outbox;
    private final Consumer<OutboxMessage> delivery;
    private final String instanceId;
    private final boolean autoStart;
    private final AtomicBoolean running = new AtomicBoolean();
    private final CountDownLatch watchStarted = new CountDownLatch(1);

    private volatile MongoCursor<?> cursor;
    private ExecutorService scheduler;
    private Thread changeStreamThread;

    @Autowired
    public OutboxRelay(
            MongoTemplate mongoTemplate,
            Clock clock,
            AppProperties properties,
            OutboxDeliverer deliverer,
            Environment environment) {
        this(
                mongoTemplate,
                clock,
                properties.outbox(),
                deliverer::deliver,
                Arrays.stream(environment.getActiveProfiles()).noneMatch("test"::equals));
    }

    public OutboxRelay(
            MongoTemplate mongoTemplate,
            Clock clock,
            AppProperties.Outbox outbox,
            Consumer<OutboxMessage> delivery) {
        this(mongoTemplate, clock, outbox, delivery, false);
    }

    private OutboxRelay(
            MongoTemplate mongoTemplate,
            Clock clock,
            AppProperties.Outbox outbox,
            Consumer<OutboxMessage> delivery,
            boolean autoStart) {
        this.mongoTemplate = mongoTemplate;
        this.clock = clock;
        this.outbox = outbox;
        this.delivery = delivery;
        this.autoStart = autoStart;
        this.instanceId = java.util.UUID.randomUUID().toString();
    }

    private AutomationMetrics metrics;

    @Autowired(required = false)
    void setMetrics(AutomationMetrics metrics) {
        this.metrics = metrics;
    }

    public synchronized void dispatchDue() {
        while (true) {
            OutboxMessage claimed = claim();
            if (claimed == null) {
                return;
            }
            try {
                delivery.accept(claimed);
            } catch (RuntimeException ex) {
                recordFailure(claimed, ex);
                continue;
            }
            markSent(claimed);
        }
    }

    @Override
    public void start() {
        if (!running.compareAndSet(false, true)) {
            return;
        }
        long delay = Math.max(outbox.pollInterval().toMillis(), 1);
        scheduler = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "outbox-poll-" + instanceId);
            thread.setDaemon(true);
            return thread;
        });
        scheduler.execute(() -> pollLoop(delay));
        changeStreamThread = new Thread(this::watchInserts, "outbox-change-stream-" + instanceId);
        changeStreamThread.setDaemon(true);
        changeStreamThread.start();
        try {
            watchStarted.await(10, TimeUnit.SECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void stop() {
        if (!running.compareAndSet(true, false)) {
            return;
        }
        if (scheduler != null) {
            scheduler.shutdownNow();
        }
        MongoCursor<?> open = cursor;
        if (open != null) {
            open.close();
        }
        if (changeStreamThread != null) {
            changeStreamThread.interrupt();
            try {
                changeStreamThread.join(2_000);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public boolean isAutoStartup() {
        return autoStart;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE;
    }

    private void pollLoop(long delayMillis) {
        while (running.get()) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                return;
            }
            safeDispatch();
        }
    }

    private void safeDispatch() {
        if (!running.get()) {
            return;
        }
        try {
            dispatchDue();
        } catch (RuntimeException ex) {
            log.warn("outbox dispatch failed error={}", ex.getClass().getSimpleName());
        }
    }

    private void watchInserts() {
        while (running.get()) {
            try {
                ensureCollection();
                try (MongoCursor<ChangeStreamDocument<Document>> opened = openCursor()) {
                    cursor = opened;
                    watchStarted.countDown();
                    while (running.get() && opened.hasNext()) {
                        opened.next();
                        safeDispatch();
                    }
                }
            } catch (RuntimeException ex) {
                if (running.get()) {
                    log.warn("outbox change stream interrupted; reopening error={}", ex.getClass().getSimpleName());
                    pause();
                }
            }
        }
    }

    private void ensureCollection() {
        if (!mongoTemplate.collectionExists(COLLECTION)) {
            mongoTemplate.createCollection(COLLECTION);
        }
    }

    private MongoCursor<ChangeStreamDocument<Document>> openCursor() {
        MongoCollection<Document> collection = mongoTemplate.getCollection(COLLECTION);
        List<Bson> pipeline = List.of(Aggregates.match(Filters.eq("operationType", "insert")));
        try {
            BsonTimestamp startAt = new BsonTimestamp((int) (Instant.now().getEpochSecond() - 2), 1);
            return collection.watch(pipeline).startAtOperationTime(startAt).iterator();
        } catch (RuntimeException ex) {
            return collection.watch(pipeline).iterator();
        }
    }

    private OutboxMessage claim() {
        Instant now = clock.instant();
        Query query = new Query(new Criteria().andOperator(
                Criteria.where("status").is(PENDING),
                Criteria.where("nextAttemptAt").lte(now),
                new Criteria().orOperator(
                        Criteria.where("lockedUntil").is(null),
                        Criteria.where("lockedUntil").lt(now))));
        query.with(Sort.by(Sort.Direction.ASC, "nextAttemptAt"));
        Update update = new Update()
                .set("lockedBy", instanceId)
                .set("lockedUntil", now.plus(outbox.leaseDuration()))
                .set("updatedAt", now);
        return mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), OutboxMessage.class);
    }

    private void markSent(OutboxMessage message) {
        Instant now = clock.instant();
        Query query = ownerQuery(message.messageId());
        Update update = new Update()
                .set("status", SENT)
                .set("sentAt", now)
                .set("updatedAt", now)
                .unset("lockedBy")
                .unset("lockedUntil");
        mongoTemplate.updateFirst(query, update, OutboxMessage.class);
    }

    private void recordFailure(OutboxMessage message, RuntimeException error) {
        int attempts = message.attempts() + 1;
        Instant now = clock.instant();
        Update update = new Update()
                .set("attempts", attempts)
                .set("updatedAt", now)
                .unset("lockedBy")
                .unset("lockedUntil");
        if (attempts >= outbox.maxAttempts()) {
            update.set("status", FAILED);
            if (metrics != null) {
                metrics.outboxFailed(message.destination());
            }
            log.error(
                    "outbox_message_failed messageId={} destination={} attempts={} error={}",
                    message.messageId(),
                    message.destination(),
                    attempts,
                    error.getClass().getSimpleName());
        } else {
            update.set("nextAttemptAt", now.plus(backoff(attempts)));
            log.warn(
                    "outbox_delivery_failed messageId={} destination={} attempts={} error={}",
                    message.messageId(),
                    message.destination(),
                    attempts,
                    error.getClass().getSimpleName());
        }
        mongoTemplate.updateFirst(ownerQuery(message.messageId()), update, OutboxMessage.class);
    }

    private Query ownerQuery(String messageId) {
        return Query.query(Criteria.where("_id").is(messageId).and("lockedBy").is(instanceId).and("status").is(PENDING));
    }

    static Duration backoff(int attempts) {
        int shift = Math.min(Math.max(attempts - 1, 0), 30);
        long seconds = 1L << shift;
        return Duration.ofSeconds(Math.min(seconds, Duration.ofMinutes(5).toSeconds()));
    }

    private static void pause() {
        try {
            Thread.sleep(200);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
