package io.panda.application.codehost.port;

import io.panda.application.command.codehost.PublishCodeChangesCommand;
import io.panda.application.command.workspace.PrepareWorkspaceCommand;
import io.panda.application.command.workspace.ResetWorkspaceCommand;
import io.panda.domain.model.codehost.CodeChangeRef;
import io.panda.domain.model.workspace.PreparedWorkspace;
import java.util.List;

public interface CodeHostPort {

    List<CodeChangeRef> publish(PublishCodeChangesCommand command);

    List<String> configuredRepositories();

    PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command);

    default void resetWorkspace(ResetWorkspaceCommand command) {}
}
