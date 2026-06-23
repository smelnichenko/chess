package io.schnappy.chess.filter;

import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;

class InternalCallerFilterTest {

    private static final String ADMIN =
            "spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-admin";
    private static final String XFCC_HEADER = "X-Forwarded-Client-Cert";

    @Test
    void configuredMatchingLastElement_proceeds() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, xfccElement(ADMIN));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void configuredWrongUri_forbidden() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, xfccElement(
                "spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chess"));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void spoofedFirstElementRealLastNonAdmin_forbidden() throws Exception {
        // Attacker prepends a forged admin element; the real proxy-appended last
        // element is a non-admin SA. Last-element selection must win → 403.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER,
                xfccElement(ADMIN) + ","
                        + xfccElement("spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chat"));
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void missingXfccHeader_forbidden() throws Exception {
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void unsetConfig_proceeds() throws Exception {
        // Disabled: no XFCC header at all, yet the request still passes through.
        var filter = new InternalCallerFilter("");
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void nonInternalPath_notFiltered() throws Exception {
        // shouldNotFilter short-circuits anything outside /internal/.
        var filter = new InternalCallerFilter(ADMIN);
        var request = new MockHttpServletRequest("GET", "/api/games");
        request.setServletPath("/api/games");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void nullConfig_disablesAndProceeds() throws Exception {
        // A null property collapses to "" so the check is disabled.
        var filter = new InternalCallerFilter(null);
        var request = internalRequest();
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.getRequest()).isSameAs(request);
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
    }

    @Test
    void emptyServletPath_fallsBackToRequestUri() throws Exception {
        // When servletPath is empty the filter must derive the path from the URI
        // so /internal traffic is still guarded.
        var filter = new InternalCallerFilter(ADMIN);
        var request = new MockHttpServletRequest("GET", "/internal/membership");
        request.setServletPath("");
        request.setRequestURI("/internal/membership");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        // No XFCC header → guarded path rejects with 403 (proves it was filtered).
        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void xfccWithoutUriKey_forbidden() throws Exception {
        // The trailing element carries no URI= pair, so no caller can be derived.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, "By=spiffe://x;Hash=abc123;Subject=\"\"");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    @Test
    void xfccElementWithBarePair_isSkipped() throws Exception {
        // A pair with no '=' is ignored; the URI= pair after it still matches.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, "bare;URI=" + ADMIN);
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void quotedUriValue_isUnquotedBeforeCompare() throws Exception {
        // Istio may quote the URI value; the comparison must strip the quotes.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, "By=spiffe://x;Hash=abc;URI=\"" + ADMIN + "\"");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_OK);
        assertThat(chain.getRequest()).isSameAs(request);
    }

    @Test
    void onlyEmptyXfccElements_yieldNoCaller_forbidden() throws Exception {
        // A non-blank header that splits into only empty elements → lastElement
        // returns null → no caller → 403. The leading commas keep it past the
        // isBlank() short-circuit so the element scan itself is exercised.
        var filter = new InternalCallerFilter(ADMIN);
        var request = internalRequest();
        request.addHeader(XFCC_HEADER, ", ,");
        var response = new MockHttpServletResponse();
        var chain = new MockFilterChain();

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(HttpServletResponse.SC_FORBIDDEN);
        assertThat(chain.getRequest()).isNull();
    }

    private static MockHttpServletRequest internalRequest() {
        var request = new MockHttpServletRequest("GET", "/internal/membership");
        request.setServletPath("/internal/membership");
        return request;
    }

    private static String xfccElement(String spiffe) {
        // Mirrors Istio's XFCC shape; URI carries the peer SPIFFE id.
        return "By=spiffe://cluster.local/ns/schnappy-production/sa/schnappy-production-chess;"
                + "Hash=abc123;Subject=\"\";URI=" + spiffe;
    }
}
