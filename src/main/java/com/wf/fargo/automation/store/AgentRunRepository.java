package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.AgentRun;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AgentRunRepository extends MongoRepository<AgentRun, String> {}
