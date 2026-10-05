package com.wf.fargo.automation.admin;

public final class AdminViews {

    private AdminViews() {}

    public record JobView(String jobId, String status, int paused, int batches) {}

    public record PublishedTemplate(String templateId, int version, String status) {}
}
