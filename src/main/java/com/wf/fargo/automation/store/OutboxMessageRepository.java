package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.OutboxMessage;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface OutboxMessageRepository extends MongoRepository<OutboxMessage, String> {}
