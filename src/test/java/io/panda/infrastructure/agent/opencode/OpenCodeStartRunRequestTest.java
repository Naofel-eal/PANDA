package io.panda.infrastructure.agent.opencode;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.panda.application.command.agent.StartAgentRunCommand;
import io.panda.domain.model.workflow.WorkflowPhase;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class OpenCodeStartRunRequestTest {

    @Test
    @DisplayName("Given an agent start command when the OpenCode request is built then execution preferences and secrets are mapped explicitly")
    void givenAgentStartCommand_whenTheOpenCodeRequestIsBuilt_thenExecutionPreferencesAndSecretsAreMappedExplicitly() {
        StartAgentRunCommand command = StartAgentRunCommand.start(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            WorkflowPhase.IMPLEMENTATION,
            "Implement SCRUM-12",
            Map.of("workItemKey", "SCRUM-12")
        );

        OpenCodeStartRunRequest request = OpenCodeStartRunRequest.from(command, new OpenCodeAgentConfig() {
            @Override
            public Optional<String> model() {
                return Optional.of("gpt-5.4");
            }

            @Override
            public Optional<String> smallModel() {
                return Optional.of("gpt-5.4-mini");
            }

            @Override
            public Optional<String> openAiApiKey() {
                return Optional.of("oa-key");
            }

            @Override
            public Optional<String> anthropicApiKey() {
                return Optional.empty();
            }

            @Override
            public Optional<String> geminiApiKey() {
                return Optional.of("gm-key");
            }

            @Override
            public Optional<String> copilotGithubToken() {
                return Optional.of("gh-token");
            }

            @Override
            public Optional<String> awsRegion() {
                return Optional.of("us-east-1");
            }

            @Override
            public Optional<String> llmHubClientId() {
                return Optional.of("client-id");
            }

            @Override
            public Optional<String> llmHubClientSecret() {
                return Optional.of("client-secret");
            }

            @Override
            public Optional<String> llmHubTenantId() {
                return Optional.of("tenant-id");
            }

            @Override
            public Optional<String> llmHubArn() {
                return Optional.of("arn:aws:iam::123:role/test");
            }

            @Override
            public Optional<String> llmHubResource() {
                return Optional.of("resource-id");
            }
        });

        assertEquals(command.commandId(), request.commandId());
        assertEquals(command.workflowId(), request.workflowId());
        assertEquals(command.agentRunId(), request.agentRunId());
        assertEquals(command.type(), request.type());
        assertEquals(command.phase(), request.phase());
        assertEquals(command.objective(), request.objective());
        assertEquals(command.inputSnapshot(), request.inputSnapshot());
        assertEquals("gpt-5.4", request.execution().model());
        assertEquals("gpt-5.4-mini", request.execution().smallModel());
        assertEquals("oa-key", request.execution().openAiApiKey());
        assertNull(request.execution().anthropicApiKey());
        assertEquals("gm-key", request.execution().geminiApiKey());
        assertEquals("gh-token", request.execution().copilotGithubToken());
        assertEquals("us-east-1", request.execution().awsRegion());
        assertEquals("client-id", request.execution().llmHubClientId());
        assertEquals("client-secret", request.execution().llmHubClientSecret());
        assertEquals("tenant-id", request.execution().llmHubTenantId());
        assertEquals("arn:aws:iam::123:role/test", request.execution().llmHubArn());
        assertEquals("resource-id", request.execution().llmHubResource());
    }
}
