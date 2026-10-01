package bosca.ide.ui;

import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowFactory;
import com.intellij.ui.content.Content;
import org.jetbrains.annotations.NotNull;

/** Registers only the stable tool-window creation hook, avoiding Kotlin bridges for experimental defaults. */
public final class BoscaToolWindowFactory implements ToolWindowFactory, DumbAware {
    @Override
    public void createToolWindowContent(@NotNull Project project, @NotNull ToolWindow toolWindow) {
        BoscaToolWindowPanel panel = new BoscaToolWindowPanel(project);
        Content content = toolWindow.getContentManager().getFactory().createContent(panel, "Overview", false);
        content.setDisposer(panel);
        toolWindow.getContentManager().addContent(content);

        BoscaPipelinesPanel pipelinesPanel = new BoscaPipelinesPanel(project);
        Content pipelines = toolWindow.getContentManager().getFactory().createContent(pipelinesPanel, "Pipelines", false);
        pipelines.setDisposer(pipelinesPanel);
        toolWindow.getContentManager().addContent(pipelines);

        BoscaWorkOpsPanel workOpsPanel = new BoscaWorkOpsPanel(project);
        Content workOps = toolWindow.getContentManager().getFactory().createContent(workOpsPanel, "WorkOps", false);
        workOps.setDisposer(workOpsPanel);
        toolWindow.getContentManager().addContent(workOps);

        BoscaPullRequestsPanel pullRequestsPanel = new BoscaPullRequestsPanel(project);
        Content pullRequests = toolWindow.getContentManager().getFactory().createContent(pullRequestsPanel, "Pull Requests", false);
        pullRequests.setDisposer(pullRequestsPanel);
        toolWindow.getContentManager().addContent(pullRequests);
    }
}
