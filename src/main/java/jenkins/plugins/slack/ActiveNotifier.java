package jenkins.plugins.slack;


import hudson.EnvVars;
import hudson.Util;
import hudson.model.AbstractBuild;
import hudson.model.AbstractProject;
import hudson.model.BuildListener;
import hudson.model.Cause;
import hudson.model.CauseAction;
import hudson.model.Hudson;
import hudson.model.Project;
import hudson.model.Result;
import hudson.model.Run;
import hudson.scm.ChangeLogSet;
import hudson.scm.ChangeLogSet.AffectedFile;
import hudson.scm.ChangeLogSet.Entry;
import hudson.tasks.test.AbstractTestResultAction;
import hudson.triggers.SCMTrigger;
import hudson.util.LogTaskListener;
import jenkins.model.Jenkins;
import org.apache.commons.lang.StringUtils;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static java.util.logging.Level.INFO;
import static java.util.logging.Level.SEVERE;

@SuppressWarnings("rawtypes")
public class ActiveNotifier implements FineGrainedNotifier {

    private static final Logger logger = Logger.getLogger(SlackListener.class.getName());

    SlackNotifier notifier;
    BuildListener listener;

    private static ExecutorService executorService =
            new ThreadPoolExecutor(10, 10, 0L, TimeUnit.MILLISECONDS, new LinkedBlockingQueue<Runnable>(20));

    public ActiveNotifier(SlackNotifier notifier, BuildListener listener) {
        super();
        this.notifier = notifier;
        this.listener = listener;
    }

    public void started(AbstractBuild build) {
        executorService.execute(new StartedTask(build));
    }

    public void completed(AbstractBuild build) {
        executorService.execute(new CompletedTask(build));
    }

    public void deleted(AbstractBuild r) {
    }

    public void finalized(AbstractBuild r) {
    }

    private SlackService getSlack(AbstractBuild r) {
        AbstractProject<?, ?> project = r.getProject();
        String projectRoom = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getRoom());
        String teamDomain = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getTeamDomain());
        String token = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class).getToken());

        EnvVars env = null;
        try {
            env = r.getEnvironment(listener);
        } catch (Exception e) {
            listener.getLogger().println("Error retrieving environment vars: " + e.getMessage());
            env = new EnvVars();
        }
        teamDomain = env.expand(teamDomain);
        token = env.expand(token);
        projectRoom = env.expand(projectRoom);

        return notifier.newSlackService(teamDomain, token, projectRoom);
    }

    private void notifyStart(AbstractBuild build, String message) {
        AbstractProject<?, ?> project = build.getProject();
        AbstractBuild<?, ?> previousBuild = project.getLastBuild().getPreviousCompletedBuild();
        if (previousBuild == null) {
            getSlack(build).publish(message, "good");
        } else {
            getSlack(build).publish(message, getBuildColor(previousBuild));
        }
    }

    String getChanges(AbstractBuild r) {
        if (!r.hasChangeSetComputed()) {
            logger.info("No change set computed...");
            return null;
        }
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        Set<AffectedFile> files = new HashSet<AffectedFile>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
            files.addAll(entry.getAffectedFiles());
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            return null;
        }
        Set<String> authors = new HashSet<String>();
        for (Entry entry : entries) {
            authors.add(entry.getAuthor().getDisplayName());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Started by changes from ");
        message.append(StringUtils.join(authors, ", "));
        message.append(" (");
        message.append(files.size());
        message.append(" file(s) changed)");
        return message.appendOpenLink().toString();
    }

    String getCommitList(AbstractBuild r) {
        ChangeLogSet changeSet = r.getChangeSet();
        List<Entry> entries = new LinkedList<Entry>();
        for (Object o : changeSet.getItems()) {
            Entry entry = (Entry) o;
            logger.info("Entry " + o);
            entries.add(entry);
        }
        if (entries.isEmpty()) {
            logger.info("Empty change...");
            Cause.UpstreamCause c = (Cause.UpstreamCause)r.getCause(Cause.UpstreamCause.class);
            if (c == null) {
                return "No Changes.";
            }
            String upProjectName = c.getUpstreamProject();
            int buildNumber = c.getUpstreamBuild();
            AbstractProject project = Hudson.getInstance().getItemByFullName(upProjectName, AbstractProject.class);
            AbstractBuild upBuild = (AbstractBuild)project.getBuildByNumber(buildNumber);
            return getCommitList(upBuild);
        }
        Set<String> commits = new HashSet<String>();
        for (Entry entry : entries) {
            StringBuffer commit = new StringBuffer();
            commit.append(entry.getMsg());
            commit.append(" [").append(entry.getAuthor().getDisplayName()).append("]");
            commits.add(commit.toString());
        }
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.append("Changes:\n- ");
        message.append(StringUtils.join(commits, "\n- "));
        return message.toString();
    }

    static String getBuildColor(AbstractBuild r) {
        Result result = r.getResult();
        if (result == Result.SUCCESS) {
            return "good";
        } else if (result == Result.FAILURE) {
            return "danger";
        } else {
            return "warning";
        }
    }

    String getBuildStatusMessage(AbstractBuild r, boolean includeTestSummary, boolean includeCustomMessage) {
        MessageBuilder message = new MessageBuilder(notifier, r);
        message.appendStatusMessage();
        message.appendDuration();
        message.appendOpenLink();
        if (includeTestSummary) {
            message.appendTestSummary();
        }
        if (includeCustomMessage) {
            message.appendCustomMessage();
        }
        return message.toString();
    }

    public static class MessageBuilder {

        private StringBuffer message;
        private SlackNotifier notifier;
        private AbstractBuild build;

        public MessageBuilder(SlackNotifier notifier, AbstractBuild build) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;
            startMessage();
        }

        public MessageBuilder(SlackNotifier notifier, AbstractBuild build, boolean verifyQa3Tests) {
            this.notifier = notifier;
            this.message = new StringBuffer();
            this.build = build;

            if (verifyQa3Tests) {
                appendAlertIfAnyOfQa3TestsBuildIsFailing();
            }
            startMessage();
        }

        public void appendAlertIfAnyOfQa3TestsBuildIsFailing() {
            String userId = getUserId(build);
            // this alert only has sense if someone is trying to copy release artifacts from DEV repo to PROD repo
            // by building Copy_Artifact_To_Prod job
            // for any other job this alert can be ignored
            if (build.getProject().getName().toLowerCase().contains("copy")) {

                List<String> brokenQa3TestBuilds = getBrokenQa3TestBuilds();
                if (!brokenQa3TestBuilds.isEmpty()) {
                    appendSendToEverybody();
                    message.append(": Watch out everybody!!! ");
                    appendSendTo(userId);
                    message.append(" is trying to release when there are QA3 tests failing!!! Whoever punch him/her first will get a star. :punch: \n");
                    message.append("Broken QA3 Tests:\n");
                    for (String brokenQa3TestBuild: brokenQa3TestBuilds) {
                        message.append(brokenQa3TestBuild + "\n");
                    }
                }
            }
        }

        public MessageBuilder appendStatusMessage() {
            message.append(this.escape(getStatusMessage(build)));
            return this;
        }

        static String getStatusMessage(AbstractBuild r) {
            if (r.isBuilding()) {
                return "Starting...";
            }
            Result result = r.getResult();
            Run previousBuild = r.getProject().getLastBuild().getPreviousBuild();
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if (result == Result.SUCCESS && previousResult == Result.FAILURE) {
                return "Back to normal";
            }
            if (result == Result.FAILURE && previousResult == Result.FAILURE) {
                return "Still Failing";
            }
            if (result == Result.SUCCESS) {
                return "Success";
            }
            if (result == Result.FAILURE) {
                return "Failure";
            }
            if (result == Result.ABORTED) {
                return "Aborted";
            }
            if (result == Result.NOT_BUILT) {
                return "Not built";
            }
            if (result == Result.UNSTABLE) {
                return "Unstable";
            }
            return "Unknown";
        }

        public MessageBuilder append(String string) {
            message.append(this.escape(string));
            return this;
        }

        public MessageBuilder append(Object string) {
            message.append(this.escape(string.toString()));
            return this;
        }

        private MessageBuilder startMessage() {
            appendBrokenBuildNotificationAddressedToUserWhoTriggeredBuild();
            message.append(this.escape(build.getProject().getFullDisplayName()));
            appendBranch();
            message.append(" - ");
            message.append(this.escape(build.getDisplayName()));
            message.append(" ");

            return this;
        }

        private void appendBranch() {
            String buildBranch = getBuildBranch();
            if (buildBranch != null) {
                message.append("(branch: ").append(buildBranch).append(")");
            }
        }

        private void appendBrokenBuildNotificationAddressedToUserWhoTriggeredBuild() {
            String userId = getUserId(build);
            if (build.getResult() == Result.FAILURE && userId != null) {
                appendSendTo(userId);
                String buildBranch = getBuildBranch();
                if ("stable".equalsIgnoreCase(buildBranch)) {
                    message.append(": You have broken a STABLE build. Please fix it and don't let your teammates waiting! :strobe: \n");
                    String firstFailedBuildUserId = getUserId(findFirstFailedBuild());
                    if (firstFailedBuildUserId != null && !userId.equals(firstFailedBuildUserId)) {
                        appendSendTo(firstFailedBuildUserId);
                        message.append(": You are a reason why your teammate build has failed. Please fix it and apologies!\n");
                    }
                } else {
                    message.append(": Just a kind reminder that your build has failed. Don't shoot the messenger. :innocent: \n");
                }
            }
        }

        private String getBuildBranch() {
            try {
                String buildBranch = build.getEnvironment().get("BUILD_BRANCH");
                return buildBranch != null ? buildBranch : build.getEnvironment().get("BRANCH");
            } catch (Exception e) {
                return null;
            }
        }

        public MessageBuilder appendOpenLink() {
            String url = notifier.getBuildServerUrl() + build.getUrl();
            message.append(" (<").append(url).append("|Open>)");
            return this;
        }

        public MessageBuilder appendDuration() {
            message.append(" after ");
            message.append(build.getDurationString());
            return this;
        }

        public MessageBuilder appendTestSummary() {
            AbstractTestResultAction<?> action = this.build.getAction(AbstractTestResultAction.class);
            if (action != null) {
                int total = action.getTotalCount();
                int failed = action.getFailCount();
                int skipped = action.getSkipCount();
                message.append("\nTest Status:\n");
                message.append("\tPassed: " + (total - failed - skipped));
                message.append(", Failed: " + failed);
                message.append(", Skipped: " + skipped);
            } else {
                message.append("\nNo Tests found.");
            }
            return this;
        }

        public MessageBuilder appendCustomMessage() {
            AbstractProject<?, ?> project = build.getProject();
            String customMessage = Util.fixEmpty(project.getProperty(SlackNotifier.SlackJobProperty.class)
                    .getCustomMessage());
            EnvVars envVars = new EnvVars();
            try {
                envVars = build.getEnvironment(new LogTaskListener(logger, INFO));
            } catch (IOException e) {
                logger.log(SEVERE, e.getMessage(), e);
            } catch (InterruptedException e) {
                logger.log(SEVERE, e.getMessage(), e);
            }
            message.append("\n");
            message.append(envVars.expand(customMessage));
            return this;
        }

        public void appendSendTo(String userId) {
            message.append("<@").append(userId).append(">");
        }

        public void appendSendToEverybody() {
            message.append("<!").append("channel").append(">");
        }

        public String escape(String string) {
            string = string.replace("&", "&amp;");
            string = string.replace("<", "&lt;");
            string = string.replace(">", "&gt;");

            return string;
        }

        private String getUserId(Run build) {
            Cause.UserIdCause userIdCause = findUserIdCause(build);
            return userIdCause != null ? userIdCause.getUserId() : null;
        }

        private Cause.UserIdCause findUserIdCause(Run build) {
            CauseAction causeAction = build.getAction(CauseAction.class);
            if (causeAction != null) {
                Cause.UserIdCause userIdCause = causeAction.findCause(Cause.UserIdCause.class);
                if (userIdCause != null) {
                    return userIdCause;
                } else {
                    Cause.UpstreamCause upstreamCause = causeAction.findCause(Cause.UpstreamCause.class);
                    while(upstreamCause != null) {
                        List<Cause> upstreamCauses = upstreamCause.getUpstreamCauses();
                        upstreamCause = null;
                        for (Cause cause: upstreamCauses) {
                            if (Cause.UserIdCause.class.isAssignableFrom(cause.getClass())) {
                                return (Cause.UserIdCause) cause;
                            } else if (Cause.UpstreamCause.class.isAssignableFrom(cause.getClass())) {
                                upstreamCause = (Cause.UpstreamCause) cause;
                            }
                        }
                    }
                }
            }

            return null;
        }

        public List<String> getBrokenQa3TestBuilds() {
            List<Project> projects = Jenkins.getInstance().getProjects();
            List<String> brokenProjects = new ArrayList<String>();
            for(Project p: projects) {
                logger.log(Level.INFO, p.getName().toLowerCase());
                if (p.getName().toLowerCase().contains("qa3_tests")
                        && !p.getName().toLowerCase().contains("experimental")) { // ignore experimental test builds like Responsive_Experimental_QA3_Tests
                    AbstractBuild lastBuild = p.getLastBuild();
                    if (lastBuild != null && Result.FAILURE == lastBuild.getResult()) {
                        brokenProjects.add(p.getName());
                    }
                }
            }

            return brokenProjects;
        }

        public Run findFirstFailedBuild() {
            Run lastSuccessfulBuild = build.getProject().getLastSuccessfulBuild();
            return lastSuccessfulBuild != null ? lastSuccessfulBuild.getNextBuild() : null;
        }

        public String toString() {
            return message.toString();
        }
    }

    private class StartedTask implements Runnable {
        private AbstractBuild build;

        public StartedTask(AbstractBuild build) {
            this.build = build;
        }

        public void run() {
            listener.getLogger().println("going to send slack notification in separate thread (pls don't disable me big T, it me small J)");

            long startTime = System.currentTimeMillis();

            AbstractProject<?, ?> project = build.getProject();
            SlackNotifier.SlackJobProperty jobProperty = project.getProperty(SlackNotifier.SlackJobProperty.class);

            CauseAction causeAction = build.getAction(CauseAction.class);

            if (causeAction != null) {
                Cause scmCause = causeAction.findCause(SCMTrigger.SCMTriggerCause.class);
                if (scmCause == null) {
                    MessageBuilder message = new MessageBuilder(notifier, build, true);
                    message.append(causeAction.getShortDescription());
                    notifyStart(build, message.appendOpenLink().toString());
                }
            }

            String changes = getChanges(build);
            if (changes != null) {
                notifyStart(build, changes);
            } else {
                notifyStart(build, getBuildStatusMessage(build, false, jobProperty.includeCustomMessage()));
            }

            long stopTime = System.currentTimeMillis();
            long elapsedTime = stopTime - startTime;

            listener.getLogger().println("Sending Slack notification took: " + elapsedTime + "ms at " + build.getFullDisplayName());

        }
    }

    private class CompletedTask implements Runnable {
        private AbstractBuild build;

        public CompletedTask(AbstractBuild build) {
            this.build = build;
        }

        public void run() {

            long startTime = System.currentTimeMillis();

            AbstractProject<?, ?> project = build.getProject();
            SlackNotifier.SlackJobProperty jobProperty = project.getProperty(SlackNotifier.SlackJobProperty.class);
            if (jobProperty == null) {
                logger.warning("Project " + project.getName() + " has no Slack configuration.");
                return;
            }
            Result result = build.getResult();
            AbstractBuild<?, ?> previousBuild = project.getLastBuild();
            do {
                previousBuild = previousBuild.getPreviousCompletedBuild();
            } while (previousBuild != null && previousBuild.getResult() == Result.ABORTED);
            Result previousResult = (previousBuild != null) ? previousBuild.getResult() : Result.SUCCESS;
            if ((result == Result.ABORTED && jobProperty.getNotifyAborted())
                    || (result == Result.FAILURE
                    && (previousResult != Result.FAILURE || jobProperty.getNotifyRepeatedFailure())
                    && jobProperty.getNotifyFailure())
                    || (result == Result.NOT_BUILT && jobProperty.getNotifyNotBuilt())
                    || (result == Result.SUCCESS
                    && (previousResult == Result.FAILURE || previousResult == Result.UNSTABLE)
                    && jobProperty.getNotifyBackToNormal())
                    || (result == Result.SUCCESS && jobProperty.getNotifySuccess())
                    || (result == Result.UNSTABLE && jobProperty.getNotifyUnstable())) {
                getSlack(build).publish(
                        getBuildStatusMessage(build, jobProperty.includeTestSummary(), jobProperty.includeCustomMessage()),
                        getBuildColor(build));
                if (jobProperty.getShowCommitList()) {
                    getSlack(build).publish(getCommitList(build), getBuildColor(build));
                }
            }

            long stopTime = System.currentTimeMillis();
            long elapsedTime = stopTime - startTime;

            listener.getLogger().println("Sending Slack notification took: " + elapsedTime + "ms at " + build.getFullDisplayName());
            logger.info("Sending Slack notification took: " + elapsedTime + "ms at " + build.getFullDisplayName());
        }
    }
}
