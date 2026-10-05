package com.wf.fargo.automation.approvals;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

import com.github.tomakehurst.wiremock.WireMockServer;

public final class CvamsWireMock {

    public static final String OK_TOKEN = "dev-stepup-ok";
    public static final String OK_REF = "sup_dev";

    private static WireMockServer server;

    private CvamsWireMock() {}

    public static String baseUrl() {
        return server().baseUrl();
    }

    public static WireMockServer server() {
        if (server == null) {
            server = new WireMockServer(wireMockConfig().dynamicPort());
            server.start();
            stubHappy();
        }
        return server;
    }

    public static void stubHappy() {
        WireMockServer current = server();
        current.resetAll();
        current.stubFor(post(urlEqualTo("/step-up/verify"))
                .withRequestBody(containing(OK_TOKEN))
                .atPriority(1)
                .willReturn(okJson("{\"valid\":true,\"stepUpRef\":\"" + OK_REF + "\"}")));
        current.stubFor(post(urlEqualTo("/step-up/verify"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(401).withBody("{\"valid\":false}")));
    }

    public static void delaySuccess(int millis) {
        WireMockServer current = server();
        current.resetAll();
        current.stubFor(post(urlEqualTo("/step-up/verify"))
                .withRequestBody(containing(OK_TOKEN))
                .atPriority(1)
                .willReturn(okJson("{\"valid\":true,\"stepUpRef\":\"" + OK_REF + "\"}").withFixedDelay(millis)));
        current.stubFor(post(urlEqualTo("/step-up/verify"))
                .atPriority(5)
                .willReturn(aResponse().withStatus(401).withBody("{\"valid\":false}")));
    }

    public static void stubUnavailable() {
        WireMockServer current = server();
        current.resetAll();
        current.stubFor(post(urlEqualTo("/step-up/verify")).willReturn(aResponse().withStatus(503)));
    }
}
