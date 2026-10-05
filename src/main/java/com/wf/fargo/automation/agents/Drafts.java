package com.wf.fargo.automation.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wf.fargo.automation.common.CanonicalJson;
import com.wf.fargo.automation.common.Hashing;
import java.util.Map;

public final class Drafts {

    private Drafts() {}

    public static String hash(ObjectMapper mapper, String templateId, int templateVersion, Map<String, Object> params) {
        ObjectNode draft = mapper.createObjectNode();
        draft.put("templateId", templateId);
        draft.put("templateVersion", templateVersion);
        draft.set("params", mapper.valueToTree(params == null ? Map.of() : params));
        return Hashing.sha256Prefixed(CanonicalJson.of(draft));
    }
}
