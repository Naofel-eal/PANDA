package io.nud.application.codehost.port;

import io.nud.application.command.codehost.PublishCodeChangesCommand;
import io.nud.application.command.workspace.PrepareWorkspaceCommand;
import io.nud.domain.model.codehost.CodeChangeRef;
import io.nud.domain.model.workspace.PreparedWorkspace;
import java.util.List;

public interface CodeHostPort {

    List<CodeChangeRef> publish(PublishCodeChangesCommand command);

    List<String> configuredRepositories();

    PreparedWorkspace prepareWorkspace(PrepareWorkspaceCommand command);
}
