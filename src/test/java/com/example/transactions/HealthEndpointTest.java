package com.example.transactions;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestExecutionListeners;
import org.springframework.test.context.support.DependencyInjectionTestExecutionListener;
import org.springframework.test.context.web.ServletTestExecutionListener;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.endsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@TestExecutionListeners(
        listeners = {ServletTestExecutionListener.class, DependencyInjectionTestExecutionListener.class},
        mergeMode = TestExecutionListeners.MergeMode.REPLACE_DEFAULTS
)
class HealthEndpointTest {
    @Autowired
    private MockMvc mockMvc;

    @Test
    void healthEndpointReportsApplicationUpWithoutExposingComponentDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    @Test
    void actuatorDiscoveryListsOnlyTheHealthEndpoint() throws Exception {
        mockMvc.perform(get("/actuator"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$._links.health.href", endsWith("/actuator/health")))
                .andExpect(jsonPath("$._links.info").doesNotExist());
    }
}
