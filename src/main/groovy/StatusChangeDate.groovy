import com.atlassian.jira.avatar.AvatarService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.ConstantsManager
import com.atlassian.jira.config.properties.ApplicationProperties
import com.atlassian.jira.event.type.EventDispatchOption
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.changehistory.ChangeHistoryItem
import com.atlassian.jira.issue.changehistory.metadata.HistoryMetadataManager
import com.atlassian.jira.issue.comments.CommentManager
import com.atlassian.jira.issue.fields.CustomField
import com.atlassian.jira.issue.fields.rest.json.beans.JiraBaseUrls
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.status.Status
import com.atlassian.jira.project.Project
import com.atlassian.jira.project.ProjectManager
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.I18nHelper
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import org.apache.log4j.Level
import org.apache.log4j.Logger
import ru.mail.jira.plugins.groovy.api.script.ParamType
import ru.mail.jira.plugins.groovy.api.script.WithParam

import java.sql.Timestamp

@WithParam(displayName = 'Project key (case-insensitive)', type = ParamType.STRING)
String projectKey

@WithParam(displayName = 'From Status (case-insensitive)', type = ParamType.STRING)
String fromStatus

@WithParam(displayName = 'To Status (case-insensitive)', type = ParamType.STRING)
String toStatus

@WithParam(displayName = 'Field to write date (case-insensitive)', type = ParamType.STRING)
String dateFieldName

Locale locale = Locale.GERMANY

def statusChangeDateWriter = new StatusChangeDateWriter(
        ComponentAccessor.getProjectManager(),
        ComponentAccessor.getWorkflowManager(),
        ComponentAccessor.getIssueManager(),
        ComponentAccessor.getComponentOfType(IssueIndexingService),
        ComponentAccessor.getConstantsManager(),
        ComponentAccessor.getComponentOfType(HistoryMetadataManager),
        ComponentAccessor.getJiraAuthenticationContext(),
        ComponentAccessor.i18nHelperFactory.getInstance(locale),
        ComponentAccessor.getComponentOfType(JiraBaseUrls),
        ComponentAccessor.getAvatarService(),
        ComponentAccessor.getApplicationProperties(),
        ComponentAccessor.getCommentManager())

if (projectKey == null) {
    throw new RuntimeException("Project key is required")
}
if (fromStatus == null) {
    throw new RuntimeException("Old status name is required")
}
if (toStatus == null) {
    throw new RuntimeException("New status name is required")
}
if (dateFieldName == null) {
    throw new RuntimeException("A field to write status changed date is required")
}

return statusChangeDateWriter.writeStatusChangeDateForAllIssues(projectKey, fromStatus, toStatus, dateFieldName)

/*
 * This script can fetch already passed status transition dates for a given project and save it to a given custom field.
 */
public class StatusChangeDateWriter {

    private static final String actionType = "writeStatusTransitionDate"

    private static final Logger log = Logger.getLogger(StatusChangeDateWriter)
    static {
        log.setLevel(Level.INFO)
    }

    private final ProjectManager projectManager
    private final WorkflowManager workflowManager
    private final IssueManager issueManager
    private final IssueIndexingService issueIndexingService
    private final ConstantsManager constantsManager
    private final HistoryMetadataManager historyMetadataManager
    private final JiraAuthenticationContext jiraAuthenticationContext
    private final I18nHelper i18nHelper
    private final JiraBaseUrls jiraBaseUrls
    private final AvatarService avatarService
    private final ApplicationProperties applicationProperties
    private final CommentManager commentManager

    public StatusChangeDateWriter(
            final ProjectManager projectManager,
            final WorkflowManager workflowManager,
            final IssueManager issueManager,
            final IssueIndexingService issueIndexingService,
            final ConstantsManager constantsManager,
            final HistoryMetadataManager historyMetadataManager,
            final JiraAuthenticationContext jiraAuthenticationContext,
            final I18nHelper i18nHelper,
            final JiraBaseUrls jiraBaseUrls,
            final AvatarService avatarService,
            final ApplicationProperties applicationProperties,
            final CommentManager commentManager) {
        this.projectManager = projectManager
        this.workflowManager = workflowManager
        this.issueManager = issueManager
        this.issueIndexingService = issueIndexingService
        this.constantsManager = constantsManager
        this.historyMetadataManager = historyMetadataManager
        this.jiraAuthenticationContext = jiraAuthenticationContext
        this.i18nHelper = i18nHelper
        this.jiraBaseUrls = jiraBaseUrls
        this.avatarService = avatarService
        this.applicationProperties = applicationProperties
        this.commentManager = commentManager
    }

    public String writeStatusChangeDateForAllIssues(
            final String projectKey, final String fromStatusName, final String toStatusName, final String dateFieldName) {

        final Project project = projectManager.getProjectByCurrentKeyIgnoreCase(projectKey)
        final List<Issue> allIssuesOfProject = issueManager.getIssueObjects(issueManager.getIssueIdsForProject(project.id))

        final CustomField dateField = ComponentAccessor.getCustomFieldManager().getCustomFieldObjectByName(dateFieldName);
        final Status fromStatus = constantsManager.getStatusByNameIgnoreCase(fromStatusName)
        final Status toStatus = constantsManager.getStatusByNameIgnoreCase(toStatusName)

        final String fromStatusId = fromStatus.getId()
        final String toStatusId = toStatus.getId()

        def result = new StringBuilder()
        for (Issue issue : allIssuesOfProject) {
            def mutableIssue = issueManager.getIssueByCurrentKey(issue.getKey())
            result.append(writeStatusChangeDate(mutableIssue, fromStatusId, toStatusId, dateField))
            result.append('\n')
        }
        return result
    }

    private String writeStatusChangeDate(final MutableIssue issue, final String fromStatusId, final String toStatusId, final CustomField dateField) {

        final JiraWorkflow workflow = workflowManager.getWorkflow(issue)
        final ApplicationUser changeUser = jiraAuthenticationContext.getLoggedInUser()
        if (workflow == null) {
            throw new RuntimeException("No workflow found for issue '${issue.getKey()}'")
        }

        StringBuilder result = new StringBuilder()
        result.append("Issue: ").append(issue.key).append("\n")

        // changeHistoryManager returns an immutable collection of issue objects sorted by creation date in descending order
        // see: https://docs.atlassian.com/software/jira/docs/api/7.6.1/index.html?com/atlassian/jira/issue/changehistory/ChangeHistoryManager.html
        List<ChangeHistoryItem> changeItems = ComponentAccessor.getChangeHistoryManager().getAllChangeItems(issue)

        for (ChangeHistoryItem historyItem : changeItems) {
            if (historyItem.getField() == "status") {
                Map<String, String> froms = historyItem.getFroms();
                Map<String, String> tos = historyItem.getTos();

                if (froms.containsKey(fromStatusId)) {
                    if (tos.containsKey(toStatusId)) {
                        def fieldValue = issue.getCustomFieldValue(dateField)
                        if (fieldValue == null) {
                            Timestamp date = historyItem.created;
                            result.append(" - date: ").append(date)
                            issue.setCustomFieldValue(dateField, date);
                            // webhook invocation is triggered
                            issueManager.updateIssue(changeUser, issue, EventDispatchOption.DO_NOT_DISPATCH, false)
                        }
                        if (fieldValue != null) {
                            Timestamp date = historyItem.created;
                            def savedDate = issue.getCustomFieldValue(dateField)
                            if (!date.equals(savedDate)) {
                                result.append(" - date mismatch: ${savedDate} in issue, ${date} in history\n")
                            }
                        }
                    }
                }
            }
        }
        return "${result}"
    }
}