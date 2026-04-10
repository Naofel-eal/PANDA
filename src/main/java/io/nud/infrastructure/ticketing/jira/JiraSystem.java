package io.nud.infrastructure.ticketing.jira;

public final class JiraSystem {

    public static final String ID = "jira";

    private JiraSystem() {
    }

    public static boolean matches(String value) {
        return value != null && ID.equalsIgnoreCase(value);
    }
}
