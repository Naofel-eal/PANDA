package io.devflow.application.port.codehost;

import io.devflow.application.command.codehost.PublishCodeChangesCommand;
import io.devflow.application.command.workspace.PrepareWorkspaceCommand;
import io.devflow.domain.codehost.CodeChangeRef;
import io.devflow.domain.workspace.PreparedWorkspace;
import java.util.List;

public interface CodeHostPort {

    List<CodeChangeRef> publish(PublishCodeChangesCommand command);

    List<String> configuredRepositories();

    PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command);
}
