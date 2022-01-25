package io.jenkins.plugins.upstream_revision.github;

import hudson.Extension;
import hudson.model.Cause.UpstreamCause;
import hudson.model.ItemGroup;
import hudson.model.Run;
import hudson.model.TaskListener;
import hudson.plugins.git.RevisionParameterAction;
import jenkins.branch.Branch;
import jenkins.plugins.git.AbstractGitSCMSource.SCMRevisionImpl;
import jenkins.plugins.git.GitSCMBuilder;
import jenkins.scm.api.SCMRevision;
import jenkins.scm.api.SCMRevisionAction;
import jenkins.scm.api.SCMSource;
import jenkins.scm.api.trait.SCMBuilder;
import jenkins.scm.api.trait.SCMSourceContext;
import jenkins.scm.api.trait.SCMSourceTraitDescriptor;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSource;
import org.jenkinsci.plugins.github_branch_source.GitHubSCMSourceContext;
import org.jenkinsci.plugins.github_branch_source.PullRequestSCMRevision;
import org.jenkinsci.plugins.workflow.job.WorkflowJob;
import org.jenkinsci.plugins.workflow.job.WorkflowRun;
import org.jenkinsci.plugins.workflow.multibranch.BranchJobProperty;
import org.jenkinsci.plugins.workflow.multibranch.SCMRevisionCustomizationTrait;
import org.jenkinsci.plugins.workflow.multibranch.WorkflowMultiBranchProject;
import org.kohsuke.stapler.DataBoundConstructor;

/**
 * This trait will make the build use the {@link SCMRevision} of the upstream job, if
 * <li> the current run is triggered by an {@link UpstreamCause}
 * <li> the upstream is a {@link WorkflowRun} that has a {@link SCMRevisionAction}
 * <li> the {@link SCMSource} of that upstream and of the current job are both a {@link GitHubSCMSource} and they have
 * the same remote url
 * <li> the (jenkins) branch names match (eg. PR-42 - PR-42)
 * <li> the {@link SCMRevision} includes a rev, such as {@link PullRequestSCMRevision} or {@link SCMRevisionImpl}.
 */
public class GithubUpstreamRevisionTrait extends SCMRevisionCustomizationTrait {
    private String contextLabel;

    @DataBoundConstructor
    public GithubUpstreamRevisionTrait(String contextLabel) {
        this.contextLabel = contextLabel;
    }

    public String getContextLabel() {
        return contextLabel;
    }

    @Override
    public SCMRevision customize(WorkflowRun currentBuild, TaskListener listener) {
        WorkflowJob job = currentBuild.getParent();
        BranchJobProperty property = job.getProperty(BranchJobProperty.class);
        if (property == null) {
            return null;
        }

        Branch currentBranch = property.getBranch();
        SCMSource currentScmSource = getScmSource(job, currentBranch.getSourceId());
        if (!(currentScmSource instanceof GitHubSCMSource)) {
            return null;
        }

        // must be triggered as downstream-project
        UpstreamCause upstreamCause = currentBuild.getCause(UpstreamCause.class);
        if (upstreamCause == null) {
            return null;
        }

        Run<?, ?> upstreamRun = upstreamCause.getUpstreamRun();
        if (!(upstreamRun instanceof WorkflowRun)) {
            return null;
        }

        SCMRevisionAction scmRevisionAction = upstreamRun.getAction(SCMRevisionAction.class);
        if (scmRevisionAction == null) {
            return null;
        }

        // upstream must have a GitHub branch source and remote urls must match
        WorkflowJob upstreamJob = ((WorkflowRun) upstreamRun).getParent();
        SCMSource upstreamScmSource;
        try {
            upstreamScmSource = getScmSource(upstreamJob, scmRevisionAction.getSourceId());
        } catch (IllegalStateException e) {
            return null;
        }
        if (!(upstreamScmSource instanceof GitHubSCMSource)
                || !((GitHubSCMSource) currentScmSource).getRemote().equalsIgnoreCase(
                        ((GitHubSCMSource) upstreamScmSource).getRemote())) {
            return null;
        }

        // Jenkins branch names must match
        SCMRevision upstreamScmRevision = scmRevisionAction.getRevision();
        if (!currentBranch.getHead().getName().equals(upstreamScmRevision.getHead().getName())) {
            return null;
        }

        if (upstreamScmRevision instanceof PullRequestSCMRevision) {
            listener.getLogger().println("Using upstream PR-revision");
            currentBuild.addAction(new RevisionParameterAction(((PullRequestSCMRevision) upstreamScmRevision).getPullHash()));
            return upstreamScmRevision;

        } else if (upstreamScmRevision instanceof SCMRevisionImpl) {
            listener.getLogger().println("Using upstream git revision");
            currentBuild.addAction(new RevisionParameterAction(((SCMRevisionImpl) upstreamScmRevision).getHash()));
            return upstreamScmRevision;
        }

        return null;
    }

    private SCMSource getScmSource(WorkflowJob job, String scmSourceId) {
        ItemGroup<?> parent = job.getParent();
        if (!(parent instanceof WorkflowMultiBranchProject)) {
            throw new IllegalStateException("inappropriate context");
        }
        SCMSource scmSource = ((WorkflowMultiBranchProject) parent).getSCMSource(scmSourceId);
        if (scmSource == null) {
            throw new IllegalStateException(scmSourceId + " not found");
        }
        return scmSource;
    }

    @Extension
    public static class DescriptorImpl extends SCMSourceTraitDescriptor {

        @Override
        public String getDisplayName() {
            return "Use Upstream Revision if possible";
        }

        @Override
        public Class<? extends SCMSourceContext> getContextClass() {
            return GitHubSCMSourceContext.class;
        }

        @Override
        public Class<? extends SCMSource> getSourceClass() {
            return GitHubSCMSource.class;
        }

        @Override
        public Class<? extends SCMBuilder> getBuilderClass() {
            return GitSCMBuilder.class;
        }
    }

    @Override
    public int getPrecedence() {
        return 0;
    }
}
