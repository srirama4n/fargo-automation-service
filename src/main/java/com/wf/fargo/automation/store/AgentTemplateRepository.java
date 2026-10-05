package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.AgentTemplate;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface AgentTemplateRepository extends MongoRepository<AgentTemplate, String> {}
