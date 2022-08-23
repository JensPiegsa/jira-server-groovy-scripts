import com.atlassian.jira.bc.issue.search.SearchService
import com.atlassian.jira.component.ComponentAccessor
import com.atlassian.jira.issue.Issue
import com.atlassian.jira.issue.IssueManager
import com.atlassian.jira.issue.label.Label
import com.atlassian.jira.issue.search.SearchResults
import com.atlassian.jira.jql.parser.JqlQueryParser
import com.atlassian.jira.security.JiraAuthenticationContext
import com.atlassian.jira.web.bean.PagerFilter
import org.apache.log4j.Level
import org.apache.log4j.Logger
import ru.mail.jira.plugins.groovy.api.script.ParamType
import ru.mail.jira.plugins.groovy.api.script.WithParam

import java.text.Collator

@WithParam(displayName = 'Project key (case-insensitive)', type = ParamType.STRING)
String projectKey

@WithParam(displayName = 'Sorting locale (empty for default: de-DE)', type = ParamType.STRING, optional = true)
String optionalSortingLocaleName

def labelFinder = new LabelFinder(
        ComponentAccessor.getComponent(SearchService),
        ComponentAccessor.getJiraAuthenticationContext(),
        ComponentAccessor.getComponent(JqlQueryParser),
        ComponentAccessor.getIssueManager()
)

return labelFinder.findAll(projectKey, optionalSortingLocaleName)

public class LabelFinder {

    private static final Logger log = Logger.getLogger(LabelFinder)
    static {
        log.setLevel(Level.INFO)
    }

    private final IssueManager issueManager
    private final SearchService searchService
    private final JiraAuthenticationContext jiraAuthenticationContext
    private final JqlQueryParser jqlQueryParser

    public LabelFinder(
            SearchService searchService,
            JiraAuthenticationContext jiraAuthenticationContext,
            JqlQueryParser jqlQueryParser,
            IssueManager issueManager) {

        this.searchService = searchService
        this.jiraAuthenticationContext = jiraAuthenticationContext
        this.jqlQueryParser = jqlQueryParser
        this.issueManager = issueManager
    }

    public String findAll(String projectKey, String optionalSortingLocaleName) {

        def user = jiraAuthenticationContext.getLoggedInUser()

        def query = jqlQueryParser.parseQuery("project = ${projectKey}")

        SearchResults search = searchService.search(user, query, PagerFilter.getUnlimitedFilter())
        def issues = search.issues

        def labels = new HashSet<String>()
        for (Issue issue : issues) {
            def issueLabels = issue.getLabels()
            for (Label label : issueLabels) {
                labels.add(label.getLabel())
            }
        }

        String localeName = optionalSortingLocaleName != null ? optionalSortingLocaleName : "de-DE" 
        Locale locale = Locale.forLanguageTag(localeName)
        Collator collator = Collator.getInstance(locale)
        
        def sortedLabels = new ArrayList<String>(labels)
        Collections.sort(sortedLabels, collator)

        def results = new StringBuilder()
        for (String label : sortedLabels) {
            results.append(label)
            results.append('\n')
        }

        return "All labels used in project ${projectKey}:\n\n${results}"
    }
}