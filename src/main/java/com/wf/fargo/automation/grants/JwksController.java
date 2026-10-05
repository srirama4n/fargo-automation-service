package com.wf.fargo.automation.grants;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.wf.fargo.automation.agents.GrantSigningKey;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class JwksController {

    private final GrantSigningKey signingKey;

    public JwksController(GrantSigningKey signingKey) {
        this.signingKey = signingKey;
    }

    @GetMapping("/.well-known/jwks.json")
    public ObjectNode jwks() {
        ObjectNode body = JsonNodeFactory.instance.objectNode();
        ArrayNode keys = body.putArray("keys");
        keys.add(signingKey.publicJwk());
        return body;
    }
}
