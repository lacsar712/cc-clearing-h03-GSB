package com.clearing.netting.adapter.in.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end proof of the user-visible acceptance criteria, through the same
 * HTTP chain the web page uses:
 * 1. deliberately trigger a failing netting (no OPEN obligations),
 * 2. locate the failed run in the list / home feed,
 * 3. open its detail page and confirm the FAILED state with reason.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class NettingFailureFlowIntegrationTest {

    private static final ObjectMapper JSON = new ObjectMapper();

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @Test
    void failedRunCanBeLocatedInListAndOpenedInDetail() throws Exception {
        String token = login();
        HttpHeaders authed = new HttpHeaders();
        authed.setBearerAuth(token);

        // 1. Deliberately trigger failure: no OPEN obligations for this date.
        LocalDate settleDate = LocalDate.of(2032, 7, 8);
        String body = JSON.writeValueAsString(new ExecuteBody(settleDate.toString(), "USD"));
        HttpHeaders postHeaders = new HttpHeaders();
        postHeaders.setContentType(MediaType.APPLICATION_JSON);
        postHeaders.setBearerAuth(token);

        ResponseEntity<String> executed = rest.exchange(
                "/api/netting-runs", HttpMethod.POST,
                new HttpEntity<>(body, postHeaders), String.class);
        assertEquals(HttpStatus.BAD_REQUEST, executed.getStatusCode());

        // 2. Locate the failed record in the list (same endpoint as list page and home page).
        ResponseEntity<String> listResp = rest.exchange(
                "/api/netting-runs", HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertEquals(HttpStatus.OK, listResp.getStatusCode());

        JsonNode list = JSON.readTree(listResp.getBody());
        assertTrue(list.isArray());
        JsonNode failedRun = null;
        for (JsonNode r : list) {
            if (settleDate.toString().equals(r.path("settleDate").asText())
                    && "USD".equals(r.path("currency").asText())) {
                failedRun = r;
                break;
            }
        }
        assertNotNull(failedRun, "failed run must appear in the list so the page can locate it");
        assertEquals("FAILED", failedRun.path("status").asText());
        assertFalse(failedRun.path("failureReason").asText("").isBlank(),
                "failureReason must be shown in the list");
        String runId = failedRun.path("runId").asText();

        // 3. Open the detail page and confirm the failure state.
        ResponseEntity<String> detailResp = rest.exchange(
                "/api/netting-runs/" + runId, HttpMethod.GET, new HttpEntity<>(authed), String.class);
        assertEquals(HttpStatus.OK, detailResp.getStatusCode(), "detail page must open for the failed batch");

        JsonNode detail = JSON.readTree(detailResp.getBody());
        JsonNode run = detail.path("run");
        assertEquals(runId, run.path("runId").asText());
        assertEquals("FAILED", run.path("status").asText());
        assertFalse(run.path("failureReason").asText("").isBlank());
        assertTrue(detail.has("positions") && detail.get("positions").isArray());
        assertTrue(detail.has("obligations") && detail.get("obligations").isArray());
    }

    private String login() {
        ResponseEntity<String> resp = rest.exchange(
                "/api/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"username\":\"operator\",\"password\":\"op123456\"}", jsonHeaders()),
                String.class);
        assertEquals(HttpStatus.OK, resp.getStatusCode());
        try {
            return JSON.readTree(resp.getBody()).path("token").asText();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private HttpHeaders jsonHeaders() {
        HttpHeaders h = new HttpHeaders();
        h.setContentType(MediaType.APPLICATION_JSON);
        return h;
    }

    private record ExecuteBody(String settleDate, String currency) {
    }
}
