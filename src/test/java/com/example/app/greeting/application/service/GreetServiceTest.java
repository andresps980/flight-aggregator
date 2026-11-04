package com.example.app.greeting.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.mockito.BDDMockito.given;

import com.example.app.greeting.application.port.out.GreetingTemplateProvider;
import com.example.app.greeting.domain.model.Greeting;
import com.example.app.greeting.domain.model.PersonName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class GreetServiceTest {

    @Mock
    private GreetingTemplateProvider templateProvider;

    @Test
    void greetsUsingTheConfiguredTemplate() {
        given(templateProvider.template()).willReturn("Hola, %s!");
        GreetService service = new GreetService(templateProvider);

        Greeting greeting = service.greet(new PersonName("Ana"));

        assertThat(greeting.message()).isEqualTo("Hola, Ana!");
    }

    @Test
    void requiresATemplateProvider() {
        assertThatNullPointerException().isThrownBy(() -> new GreetService(null));
    }
}
