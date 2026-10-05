package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.PersonalAgent;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface PersonalAgentRepository extends MongoRepository<PersonalAgent, String> {}
