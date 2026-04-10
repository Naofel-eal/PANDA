package io.nud.infrastructure.support;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.nud.support.ReflectionTestSupport;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class JsonSupportTest {

    @Test
    @DisplayName("Given business payloads when NUD serializes and deserializes JSON then the information is preserved")
    void givenBusinessPayloads_whenNUDSerializesAndDeserializesJson_thenInformationIsPreserved() {
        JsonSupport jsonSupport = new JsonSupport();
        ReflectionTestSupport.setField(jsonSupport, "objectMapper", new ObjectMapper());

        String json = jsonSupport.toJson(Map.of("ticket", "SCRUM-1"));

        assertTrue(json.contains("SCRUM-1"));
        assertEquals(Map.of("ticket", "SCRUM-1"), jsonSupport.toMap(json));
        assertEquals(Payload.class, jsonSupport.fromJson("{\"value\":\"ok\"}", Payload.class).getClass());
    }

    @Test
    @DisplayName("Given invalid payloads when NUD parses JSON then it fails with a business-safe exception")
    void givenInvalidPayloads_whenNUDParsesJson_thenItFailsWithBusinessSafeException() {
        JsonSupport jsonSupport = new JsonSupport();
        ReflectionTestSupport.setField(jsonSupport, "objectMapper", new ObjectMapper());

        assertEquals(Map.of(), jsonSupport.toMap(" "));
        assertThrows(IllegalStateException.class, () -> jsonSupport.toMap("{"));

        Map<String, Object> cyclic = new LinkedHashMap<>();
        cyclic.put("self", cyclic);
        assertThrows(IllegalStateException.class, () -> jsonSupport.toJson(cyclic));
        assertThrows(IllegalStateException.class, () -> jsonSupport.fromJson("{", Payload.class));
    }

    public static class Payload {
        public String value;
    }
}
