import com.atlassian.jira.issue.comments.CommentManager
import org.apache.log4j.Level
import org.apache.log4j.Logger
import com.atlassian.jira.avatar.AvatarService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.config.ConstantsManager
import com.atlassian.jira.config.properties.ApplicationProperties
import com.atlassian.jira.config.properties.LookAndFeelBean
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.MutableIssue
import com.atlassian.jira.issue.changehistory.metadata.HistoryMetadata
import com.atlassian.jira.issue.changehistory.metadata.HistoryMetadataManager
import com.atlassian.jira.issue.changehistory.metadata.HistoryMetadataParticipant
import com.atlassian.jira.issue.fields.rest.json.beans.JiraBaseUrls
import com.atlassian.jira.issue.index.IndexException
import com.atlassian.jira.issue.index.IssueIndexingService
import com.atlassian.jira.issue.status.Status
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.user.ApplicationUser
import com.atlassian.jira.util.I18nHelper
import com.atlassian.jira.workflow.JiraWorkflow
import com.atlassian.jira.workflow.WorkflowManager
import com.atlassian.jira.issue.history.ChangeLogUtils
import org.ofbiz.core.entity.GenericValue
import ru.mail.jira.plugins.groovy.api.script.ParamType
import ru.mail.jira.plugins.groovy.api.script.WithParam


@WithParam(displayName = 'Issue key (case-insensitive)', type = ParamType.STRING)
String issueKey

@WithParam(displayName = 'New status name (case-insensitive) or ID', type = ParamType.STRING)
String caseInsensitiveNewStatusNameOrId

@WithParam(displayName = 'Reason', type = ParamType.STRING)
String reason

Locale locale = Locale.GERMANY

def fixStatus = new StatusCorrector(
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

if (issueKey == null) {
    throw new RuntimeException("Issue key is required")
}
if (caseInsensitiveNewStatusNameOrId == null) {
    throw new RuntimeException("New status name or ID is required")
}
if (reason == null) {
    throw new RuntimeException("Reason key is required")
}

return fixStatus.changeIssueStatus(issueKey, caseInsensitiveNewStatusNameOrId, reason)

public class StatusCorrector {

    private static final String actionType = "correctStatus"
    
    private static final Logger log = Logger.getLogger(StatusCorrector)
    static {
        log.setLevel(Level.INFO)
    }
    
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
    
    public StatusCorrector(
            WorkflowManager workflowManager,
            IssueManager issueManager,
            IssueIndexingService issueIndexingService,
            ConstantsManager constantsManager,
            HistoryMetadataManager historyMetadataManager,
            JiraAuthenticationContext jiraAuthenticationContext,
            I18nHelper i18nHelper,
            JiraBaseUrls jiraBaseUrls,
            AvatarService avatarService,
            ApplicationProperties applicationProperties,
            CommentManager commentManager) {
        
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

    public String changeIssueStatus(String caseInsensitiveIssueKey, String caseInsensitiveNewStatusNameOrId, String reason) {

        final Issue originalIssue = issueManager.getIssueByKeyIgnoreCase(caseInsensitiveIssueKey)
        final MutableIssue issue = issueManager.getIssueByKeyIgnoreCase(caseInsensitiveIssueKey)

        if (issue == null) {
            throw new RuntimeException("No issue found with key '${caseInsensitiveIssueKey}'")
        }

        final JiraWorkflow workflow = workflowManager.getWorkflow(issue)

        if (workflow == null) {
            throw new RuntimeException("No workflow found for issue '${issue.getKey()}'")
        }

        final Status currentStatus = issue.getStatus()

        final Status newStatusById = constantsManager.getStatus(caseInsensitiveNewStatusNameOrId)
        final Status newStatusByName = constantsManager.getStatusByNameIgnoreCase(caseInsensitiveNewStatusNameOrId)
        final Status newStatus = newStatusById != null ? newStatusById : newStatusByName

        if (newStatus == null) {
            throw new RuntimeException("No status found with name / id '${caseInsensitiveNewStatusNameOrId}'")
        }

        String newStatusId = newStatus.getId()
        String currentStatusId = currentStatus.getId()

        if (newStatusId == currentStatusId) {
            throw new RuntimeException("Issue '${issue.getKey()}'already in status '${currentStatus.getName()}' (ID: ${currentStatusId})")
        }

        boolean statusFoundInWorkflow = false

        for (Status status : workflow.getLinkedStatusObjects()) {
            if (status.getId() == newStatusId) {
                statusFoundInWorkflow = true
                break
            }
        }
        if (!statusFoundInWorkflow) {
            throw new RuntimeException("Status '${newStatus.name}' not part of issue workflow '${workflow.getName()}'")
        }

        workflowManager.migrateIssueToWorkflow(issue, workflow, newStatus)
        
        ApplicationUser author = jiraAuthenticationContext.getLoggedInUser()
        
        String comment = "${i18nHelper.getText('issue.field.status')} ${currentStatus.getName()} -> ${newStatus.getName()}: ${reason}"
        
        // also triggers required webhook event
        addComment(issue, comment, author)
        
        addChangeGroup(issue, originalIssue, author)
        
        reindex(issue)
        
        return comment
    }

    private void addChangeGroup(MutableIssue issue, Issue originalIssue, ApplicationUser author) {
        
        issue.setResolutionDate(originalIssue.getResolutionDate())
        
        GenericValue changeGroup = ChangeLogUtils.createChangeGroup(author, originalIssue, issue, (Collection) null, true)
        if (changeGroup == null) {
            throw new RuntimeException("Failed to create change group for issue '${originalIssue.getKey()}'")
        } else {
            historyMetadataManager.saveHistoryMetadata(changeGroup.getLong("id"), author, createHistoryMetadata(issue, author))
        }
    }

    private HistoryMetadata createHistoryMetadata(Issue issue, ApplicationUser author) {
        return HistoryMetadata.builder(actionType)
                .actor(toActor(author))
                .cause(toCause(issue))
                .generator(getGenerator())
                .description(i18nHelper.getText("issue.field.status"))
                .descriptionKey("issue.field.status")
                .activityDescription(i18nHelper.getText("issue.field.status"))
                .activityDescriptionKey("issue.field.status")
                .emailDescription(author.getEmailAddress())
                .emailDescriptionKey(author.getEmailAddress())
                .build()
    }

    private HistoryMetadataParticipant toCause(Issue issue) {
        return HistoryMetadataParticipant
                .builder("Status ID ${issue.getStatusId()}", actionType)
                .displayName("${i18nHelper.getText("issue.field.status")}: ${issue.getStatus().getName()}")
                .displayNameKey("issue.field.status")
                .build()
    }

    private HistoryMetadataParticipant toActor(ApplicationUser user) {
        return HistoryMetadataParticipant
                .builder(user.getName(), user.getClass().getName())
                .displayName(user.getDisplayName())
                .url("${jiraBaseUrls.baseUrl()}/secure/ViewProfile.jspa?name=${user.getName()}")
                .avatarUrl("${avatarService.getAvatarURL(user, user)}")
                .build()
    }

    private HistoryMetadataParticipant getGenerator() {
        LookAndFeelBean lookAndFeelBean = LookAndFeelBean.getInstance(applicationProperties)
        return HistoryMetadataParticipant.builder("Jira", "Jira")
                .avatarUrl("${jiraBaseUrls.baseUrl()}${lookAndFeelBean.getFaviconHiResUrl()}")
                .build()
    }

    private void reindex(MutableIssue issue) {
        try {
            issueIndexingService.reIndex(issue)
        } catch (IndexException e) {
            throw new RuntimeException("Failed to reindex issue", e)
        }
    }

    void addComment(Issue issue, String comment, ApplicationUser author) {
        commentManager.create(issue, author, comment, true)
    }
}
