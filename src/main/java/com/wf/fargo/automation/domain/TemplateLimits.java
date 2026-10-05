package com.wf.fargo.automation.domain;

import java.math.BigDecimal;

public record TemplateLimits(BigDecimal maxAmount, Integer maxRunsPerDay, Integer maxRunsPerPeriod) {}
