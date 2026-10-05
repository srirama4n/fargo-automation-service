package com.wf.fargo.automation.store;

import com.wf.fargo.automation.domain.Approval;
import org.springframework.data.mongodb.repository.MongoRepository;

public interface ApprovalRepository extends MongoRepository<Approval, String> {}
