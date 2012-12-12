package eu.renderserver

import org.slf4j.LoggerFactory
import org.slf4j.Logger
import safran.Client
import java.text.DecimalFormat
import server.global.Constants
import groovy.util.slurpersupport.GPathResult

/**
 *
 */
class Config {

    Logger log = LoggerFactory.getLogger(this.class)

    static DecimalFormat formatter = new DecimalFormat("00000000000000000000");

    def conf

    Config() {
        def configPath = System.getProperty("RENDERSERVER_DIR", ".")
        log.debug("configPath: " + configPath)
        File configFile = new File(configPath, "renderserver.config.xml")
        conf = new XmlSlurper().parse(configFile)
    }

    List<Client> getClients() {
        def repoList = conf.repositories?.repository
        List<Client> reps = new ArrayList<Client>()
        if (!repoList) {
            return reps
        }

        repoList.each { repository ->
            def repositoryName = repository.name.text()
            def url = repository.url.text()
            def username = repository.username.text()
            def password = repository.password.text()
            Client client = new Client(url, username, password, repositoryName)
            log.debug("created client ${client.toString()}")
            reps.add(client)
        }

        return reps
    }

    /**
     * Given the name of a render task, this method returns the command which
     * can be used to run the external process.
     * @param name name of the render task
     * @return the command by which to start the external process.
     */
    String getCommand(String name) {
        return conf?.tasks?.task?.find {
            it?.name?.text()?.equals(name)
        }?.exec?.text()
    }

    Boolean requiresEscapedUrlForCinnamonClient(String taskName) {
        return 'true'.equals(conf?.tasks?.task?.find {
            it?.name?.text()?.equals(taskName)
        }?.escapeUrlForCinnamonClient?.text())
    }

    Long getSleepDuration() {
        return Long.parseLong(conf?.sleepDuration?.text())
    }

    Integer getThreadCount() {
        return Integer.parseInt(conf?.threadCount?.text())
    }

    String getTaskQuery(Client client) {
        def lcSlurp = fetchRenderServerLifeCycle(client)
        def lcsId = lcSlurp.lifecycle.defaultState.lifecycleState.id.text()
        log.debug("lcId: '$lcsId'")
        Long defaultLCS = Long.parseLong(lcsId)
        // assumption: this LC must have a default state.

        String query = new File(conf.taskQueryFile?.text()).text
        String taskQuery = query.replace("__lcsId__", formatter.format(defaultLCS))
//        log.debug("taskQuery: \n$taskQuery")
        return taskQuery
    }

    String getTaskQueryRunningTasks(Client client) {
        def lcSlurp = fetchRenderServerLifeCycle(client)
        def lcs = lcSlurp.lifecycle.states.lifecycleState.find {
            it.sysName.equals(Constants.RENDERSERVER_RENDER_TASK_RENDERING)
        }

//        log.debug("lcs: '${lcs?.dump()}'")
        def lcsId = lcs.id.text()
        log.debug("runningTasks lcsId: '$lcsId'")
        Long defaultLCS = Long.parseLong(lcsId)
        // assumption: this LC must have a default state.

        String query = new File(conf.taskQueryFile?.text()).text
        String taskQuery = query.replace("__lcsId__", formatter.format(defaultLCS))
        return taskQuery
    }

    GPathResult fetchRenderServerLifeCycle(Client client) {
        def repo = conf.repositories.repository.find {it.name.text().equals(client.repository)}
        /*
         * The config contains the name of the RenderServer's LifeCycle.
         * For the search, we need the id.
         */
        String lifeCycleName = repo.lifeCycleName.text()
        String lifeCycle = client.getLifeCycleByName(lifeCycleName)
//        log.debug("LC: ${lifeCycle}")
        return new XmlSlurper().parseText(lifeCycle)

    }
}
