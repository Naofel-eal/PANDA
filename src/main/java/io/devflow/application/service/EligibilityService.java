package io.devflow.application.service;

import io.devflow.domain.ticketing.WorkItem;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.ArrayList;
import java.util.List;

@ApplicationScoped
public class EligibilityService {

    public EligibilityDecision evaluate(WorkItem workItem) {
        List<String> missing = new ArrayList<>();
        if (isBlank(workItem.title())) {
            missing.add("title");
        }
        if (isBlank(workItem.description())) {
            missing.add("description");
        }

        return new EligibilityDecision(missing);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    public record EligibilityDecision(List<String> missingFields) {
        public boolean hasEnoughInformation() {
            return missingFields.isEmpty();
        }
    }
}
