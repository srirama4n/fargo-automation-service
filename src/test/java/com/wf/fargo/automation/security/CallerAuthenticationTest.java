package com.wf.fargo.automation.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.wf.fargo.automation.common.ApiException;
import com.wf.fargo.automation.common.CallerContext;
import com.wf.fargo.automation.common.LogSanitizer;
import com.wf.fargo.automation.common.RawAccountNumbers;
import com.wf.fargo.automation.config.AccessTokens;
import com.wf.fargo.automation.config.CallerAuthenticationFilter;
import com.wf.fargo.automation.config.JwtKeyConfig;
import com.wf.fargo.automation.grants.McpIdentity;
import com.wf.fargo.automation.standalone.DevAuthFilter;
import jakarta.servlet.FilterChain;
import java.security.cert.X509Certificate;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CallerAuthenticationTest {

    private final AccessTokens tokens = new AccessTokens(new JwtKeyConfig(), Clock.systemUTC());
    private final CallerAuthenticationFilter filter = new CallerAuthenticationFilter(tokens);

    @Test
    void devHeaderIsIgnoredUnlessTheStandaloneFilterCopiedIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1");
        request.addHeader(CallerContext.FARGO_CUSTOMER_HEADER, "cust_9");
        filter.doFilter(request, new MockHttpServletResponse(), (FilterChain) (req, res) -> {});
        assertThat(request.getAttribute(CallerContext.CUSTOMER_ID_ATTRIBUTE)).isNull();
        assertThat(DevAuthFilter.class.getAnnotation(Profile.class).value())
                .containsExactlyInAnyOrder("standalone", "test");
        assertThat(CallerAuthenticationFilter.class.getAnnotation(Profile.class)).isNull();
    }

    @Test
    void aClientCertificateIsAnMcpIdentity() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(
                "jakarta.servlet.request.X509Certificate", new X509Certificate[] {mock(X509Certificate.class)});
        new McpIdentity().require(request);
    }

    @Test
    void tokensMaskToTheLastFourAndRawNumbersAreRejected() {
        assertThat(LogSanitizer.sanitize("move " + "acct_tok_abcdWXYZ" + " now")).isEqualTo("move ***WXYZ now");
        assertThat(LogSanitizer.sanitize("card " + "4111111111111111")).doesNotContain("4111111111111111");
        assertThat(LogSanitizer.sanitize("card " + "4111111111111111")).doesNotMatch(".*\\d{13,19}.*");
        RawAccountNumbers.reject("acct_tok_ok");
        RawAccountNumbers.reject("123456789012");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RawAccountNumbers.reject("1234567890123"))
                .isInstanceOf(ApiException.class)
                .matches(error -> "raw_account_number".equals(((ApiException) error).code()));
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> RawAccountNumbers.reject(4111111111111111L))
                .isInstanceOf(ApiException.class);
    }
}
