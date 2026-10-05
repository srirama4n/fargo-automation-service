package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.Grant;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface GrantRepository extends MongoRepository<Grant, String> {}
