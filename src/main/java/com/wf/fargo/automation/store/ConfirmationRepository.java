package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.Confirmation;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ConfirmationRepository extends MongoRepository<Confirmation, String> {}
