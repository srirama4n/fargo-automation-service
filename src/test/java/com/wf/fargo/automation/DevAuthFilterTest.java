package com.wf.fargo.automation;

import static org.assertj.core.api.Assertions.assertThat;

import com.wf.fargo.automation.standalone.DevAuthFilter;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class DevAuthFilterTest {

    private final DevAuthFilter filter = new DevAuthFilter();

    @Test
    void copiesDevHeadersOntoRequestAttributes() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(DevAuthFilter.CUSTOMER_ID_HEADER, "cust_1");
        request.addHeader(DevAuthFilter.CALLER_HEADER, "mcp");

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(DevAuthFilter.CUSTOMER_ID_ATTRIBUTE)).isEqualTo("cust_1");
        assertThat(request.getAttribute(DevAuthFilter.CALLER_ATTRIBUTE)).isEqualTo("mcp");
    }

    @Test
    void leavesAttributesUnsetWhenHeadersAreMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();

        filter.doFilter(request, new MockHttpServletResponse(), new MockFilterChain());

        assertThat(request.getAttribute(DevAuthFilter.CUSTOMER_ID_ATTRIBUTE)).isNull();
        assertThat(request.getAttribute(DevAuthFilter.CALLER_ATTRIBUTE)).isNull();
    }
}
