package com.wf.fargo.automation.domain;

import java.util.List;
import java.util.Map;

public record RunPreview(String quoteId, String effect, List<String> warnings, Map<String, Object> data) {}
