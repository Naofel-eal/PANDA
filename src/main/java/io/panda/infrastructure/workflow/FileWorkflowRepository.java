package io.panda.infrastructure.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import io.panda.application.config.ApplicationConfig;
import io.panda.application.workflow.port.WorkflowRepository;
import io.panda.domain.model.workflow.Workflow;
import io.panda.domain.model.workflow.WorkflowStatus;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;
import org.jboss.logging.Logger;

@ApplicationScoped
public class FileWorkflowRepository implements WorkflowRepository {

    private static final Logger LOG = Logger.getLogger(FileWorkflowRepository.class);
    private static final String WORKFLOWS_DIR = ".workflows";

    private final ObjectMapper objectMapper;

    @Inject
    ApplicationConfig config;

    FileWorkflowRepository() {
        this.objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .enable(SerializationFeature.INDENT_OUTPUT);
    }

    @Override
    public void save(Workflow workflow) {
        Path file = resolveFile(workflow.workflowId());
        Path tempFile = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            Files.createDirectories(file.getParent());
            WorkflowRecord record = toRecord(workflow);
            byte[] content = objectMapper.writeValueAsBytes(record);
            Files.write(tempFile, content);
            atomicMove(tempFile, file);
            LOG.debugf("Persisted workflow %s (status=%s, phase=%s)", workflow.workflowId(), workflow.status(), workflow.phase());
        } catch (IOException e) {
            LOG.errorf(e, "Failed to persist workflow %s", workflow.workflowId());
            cleanupTempFile(tempFile);
        }
    }

    @Override
    public Optional<Workflow> findById(UUID workflowId) {
        Path file = resolveFile(workflowId);
        if (!Files.exists(file)) {
            return Optional.empty();
        }
        return Optional.ofNullable(readWorkflow(file));
    }

    @Override
    public List<Workflow> findByStatus(WorkflowStatus status) {
        Path directory = workflowsDirectory();
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(directory)) {
            return files
                .filter(p -> p.getFileName().toString().endsWith(".json"))
                .map(this::readWorkflow)
                .filter(w -> w != null && w.status() == status)
                .toList();
        } catch (IOException e) {
            LOG.warnf(e, "Failed to list workflow files in %s", directory);
            return List.of();
        }
    }

    private Workflow readWorkflow(Path file) {
        try {
            WorkflowRecord record = objectMapper.readValue(file.toFile(), WorkflowRecord.class);
            return fromRecord(record);
        } catch (IOException e) {
            LOG.warnf(e, "Failed to read workflow file %s", file);
            return null;
        }
    }

    private Path resolveFile(UUID workflowId) {
        return workflowsDirectory().resolve(workflowId + ".json");
    }

    private Path workflowsDirectory() {
        return Path.of(config.workspace().runRoot()).resolve(WORKFLOWS_DIR);
    }

    private void atomicMove(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException e) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private void cleanupTempFile(Path tempFile) {
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException ignored) {
        }
    }

    private static WorkflowRecord toRecord(Workflow workflow) {
        return new WorkflowRecord(
            workflow.workflowId(),
            workflow.agentRunId(),
            workflow.ticketSystem(),
            workflow.ticketKey(),
            workflow.phase(),
            workflow.objective(),
            workflow.startedAt(),
            workflow.status(),
            workflow.publishedPRs(),
            workflow.transitions()
        );
    }

    private static Workflow fromRecord(WorkflowRecord record) {
        return Workflow.reconstitute(
            record.workflowId(),
            record.agentRunId(),
            record.ticketSystem(),
            record.ticketKey(),
            record.phase(),
            record.objective(),
            record.startedAt(),
            record.status(),
            record.publishedPRs(),
            record.transitions()
        );
    }
}
