package com.wf.fargo.automation;

import com.wf.fargo.automation.approvals.CvamsWireMock;
import com.wf.fargo.automation.approvals.StepUpVerifier;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "standalone"})
@AutoConfigureMockMvc
@Import(TestClockConfig.class)
public abstract class EmbeddedMongoTest {

    @DynamicPropertySource
    static void cvamsBaseUrl(DynamicPropertyRegistry registry) {
        registry.add("automation.cvams.base-url", CvamsWireMock::baseUrl);
    }

    @Autowired
    private MongoTemplate mongoTemplate;

    @Autowired
    private AdjustableClock clock;

    @Autowired
    private StepUpVerifier stepUp;

    @BeforeEach
    void cleanCollections() {
        clock.reset();
        stepUp.reset();
        CvamsWireMock.stubHappy();
        for (String name : mongoTemplate.getDb().listCollectionNames()) {
            if (!name.startsWith("system.")) {
                mongoTemplate.dropCollection(name);
            }
        }
    }

    protected MongoTemplate mongoTemplate() {
        return mongoTemplate;
    }
}
