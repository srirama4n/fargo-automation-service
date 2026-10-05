package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.AgentActivity;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AgentActivityRepository extends MongoRepository<AgentActivity, String> {}
