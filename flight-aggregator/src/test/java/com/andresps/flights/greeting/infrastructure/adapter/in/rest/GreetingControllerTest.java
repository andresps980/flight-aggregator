package com.andresps.flights.greeting.infrastructure.adapter.in.rest;

import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.andresps.flights.greeting.application.port.in.GreetUseCase;
import com.andresps.flights.greeting.domain.model.Greeting;
import com.andresps.flights.greeting.domain.model.PersonName;
import com.andresps.flights.shared.infrastructure.rest.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Unit test of the REST adapter: standalone MockMvc, no Spring context. The use case is mocked, so
 * only HTTP mapping and error translation are under test.
 */
class GreetingControllerTest {

    private GreetUseCase greetUseCase;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        greetUseCase = mock(GreetUseCase.class);
        mockMvc = MockMvcBuilders.standaloneSetup(new GreetingController(greetUseCase))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void returnsGreetingForGivenName() throws Exception {
        given(greetUseCase.greet(new PersonName("Ana"))).willReturn(new Greeting("Hello, Ana!"));

        mockMvc.perform(get("/api/v1/greetings").param("name", "Ana"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello, Ana!"));
    }

    @Test
    void usesDefaultNameWhenNoneIsGiven() throws Exception {
        given(greetUseCase.greet(new PersonName("World"))).willReturn(new Greeting("Hello, World!"));

        mockMvc.perform(get("/api/v1/greetings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Hello, World!"));
    }

    @Test
    void returnsProblemDetailWhenNameIsInvalid() throws Exception {
        mockMvc.perform(get("/api/v1/greetings").param("name", " "))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Invalid request"))
                .andExpect(jsonPath("$.detail").value("Name must not be blank"));
    }
}
