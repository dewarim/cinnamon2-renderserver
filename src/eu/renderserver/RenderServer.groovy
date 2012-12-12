package eu.renderserver

import safran.Client
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import server.global.Constants
import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.joran.JoranConfigurator

/**
 * Main class of the RenderServer package. The RenderServer looks up the configured repositories and
 * searches for new RenderTasks to do.
 */
class RenderServer implements Runnable {

    Logger log = LoggerFactory.getLogger(this.class)

    RenderTask currentTask

    Config conf
    Collection<Client> clients
    Collection<RenderTask> renderTasks = []

    RenderServer() {
        conf = loadConfig()
        clients = conf.getClients()
    }

    RenderServer(RenderTask task) {
        this()
        this.currentTask = task
    }

    Config loadConfig() {
        return new Config()
    }

    /**
     * Connect to the server and query each configured repository for waiting tasks.
     * @return a list of RenderTasks, which may be empty if there is nothing to do.
     */
    List<RenderTask> findNewTasks() {
        List<RenderTask> tasks = []
        clients.each {client ->
            try {
                // We assume that a session, once established, will stay valid
                // because the RenderServer is querying repeatedly.
                if (client.getSessionTicket() == null) {
                    if (!client.connect()) {
                        throw new RuntimeException("Failed to connect to ${client}")
                    }
                    log.debug("Successfully connected to ${client}")
                }
                def query = conf.getTaskQuery(client)

                // search for waiting tasks:
                def waitingTasks = new XmlSlurper().parseText(client.searchObjects(query))?.object
                waitingTasks.id.each {id ->
                    Long taskId = Long.parseLong(id.text())
                    def metadata = client.getMeta(taskId)
                    log.debug("metadata: ${taskId} ${metadata}")
                    def metaXml = new XmlSlurper().parseText(metadata)
                    def name = metaXml.metaset.find {it.'@type'.equals('render_input')}.renderTaskName?.text()
                    log.debug("taskName: ${name}")
                    if (!name) {
                        throw new RuntimeException("Found no renderTaskName.")
                    }
                    RenderTask rt = new RenderTask(client: new Client(client), id: taskId, name: name, config: conf)
                    tasks.add(rt)
                }
            }
            catch (Exception e) {
                log.debug("Connection failure: ", e)
            }
        }
        log.debug("Found ${tasks.size()} new renderTasks.")
        return tasks
    }

    void resetRunningTasks() {
        clients.each {client ->
            try {
                // We assume that a session, once established, will stay valid
                // because the RenderServer is querying repeatedly.
                if (client.getSessionTicket() == null) {
                    if (!client.connect()) {
                        throw new RuntimeException("Failed to connect to ${client}")
                    }
                    log.debug("Success: connection established..");
                }

                def query = conf.getTaskQueryRunningTasks(client)

//                log.debug("Query: $query")

                // search for running tasks:
                def runningTasks = new XmlSlurper().parseText(client.searchObjects(query))?.object

                def renderLifeCycle = client.getLifeCycleByName(Constants.RENDER_SERVER_LIFECYCLE)
                def lcId = Long.parseLong(new XmlSlurper().parseText(renderLifeCycle).lifecycle.id.text())
                log.debug("renderLC-id:" + lcId)

                runningTasks.id.each {id ->
                    Long taskId = Long.parseLong(id.text())
                    /*
                     * Attach render lifecycle (again). This should drop the render task into the default
                     * state - "new".
                     */
                    client.lock(taskId)
                    client.attachLifeCycle(taskId, lcId)
                    client.unlock(taskId)
                }
            }
            catch (Exception e) {
                log.debug("Connection failure: ", e)
            }
        }
    }

    void executeTasks() {
        log.info("Going to execute ${renderTasks.size()} tasks.")
        renderTasks.each {task ->
            try {
                task.execute()
            }
            catch (Exception e) {
                task.client.publishStackTrace("render_output", task.id, e)
            }
        }
        log.info("Task execution is finished.")
    }

    /**
     * If multiple RenderServer threads are being used, each thread should only execute one task at a time
     * before looking if there is any new work. This way, a thread will not try to execute all the other tasks
     * that have been finished by other threads already.
     */
    void executeOneTask(RenderTask task) {
        try {
            task.execute()
        }
        catch (RuntimeException e) {
            log.debug("Exception during task.execute():", e)
            task?.client?.publishStackTrace("render_output", task.id, e)
        }
    }

    void run() {
        try {
//            def thread = Thread.currentThread()
            //            log.debug("This is Thread#${thread.id} state ${thread.state} with task: ${currentTask.id} ")
            executeOneTask(currentTask)
        }
        catch (Exception e) {
            log.debug("Exception occurred while working on tasks:", e)
        }
    }

    void startThreads(Integer count, Long sleepDuration, Boolean testing) {
        if (count == null || count <= 0) {
            throw new RuntimeException("Parameter threadCount must not be null, zero or negative!")
        }
        if (sleepDuration == null || sleepDuration < 1000) {
            throw new RuntimeException("Parameter sleepDuration may not be null or lower than 1000 (milliseconds)")
        }
        Long stagger = sleepDuration / count
        Boolean keepRunning = true
        while (true) {
            // primitive rate limiting:
            def activeThreads = Thread.activeCount()
//            logThreadInfo()

            if (activeThreads > count) {
                log.warn("There are $activeThreads active threads - will not start any new ones.")
                Thread.sleep(sleepDuration)
                continue
            }
            log.debug("Active threads: $activeThreads")

            renderTasks = findNewTasks().reverse() // reverse so we can use pop.
            // if there is nothing to do, just sleep.
            if (renderTasks.isEmpty()) {
                log.debug("Nothing to do - going to sleep for $sleepDuration milliseconds")
                Thread.sleep(sleepDuration)
                continue
            }

            int currentCount = count
            if (renderTasks.size() < count) {
                currentCount = renderTasks.size()
            }

            log.debug("Starting $currentCount RenderServer threads, waiting for $stagger milliseconds between each thread start.")
            for (int x = 0; x < currentCount; x++) {
                RenderTask task = (RenderTask) renderTasks.pop()
                try {
                    (new Thread(new RenderServer(task))).start()
                }
                catch (Exception e) {
                    log.error("Error occurred during RenderServer thread instantiation.", e)
                }
                Thread.sleep(stagger)
            }

            log.debug("Sleep for $sleepDuration milliseconds")
            Thread.sleep(sleepDuration)
            if(testing){
                logThreadInfo()
            }
        }
    }

    void logThreadInfo() {
        def threadList = new Thread[Thread.activeCount()]
        Thread.enumerate(threadList)
        threadList.each {t ->
            log.debug("Thread::${t.state} ${t.alive} ${t.id} ${t.name} ${t.toString()}")
        }
    }

    void configureLogging() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        JoranConfigurator jc = new JoranConfigurator();
        jc.setContext(context);
        context.reset(); // override default configuration
        jc.doConfigure(new File('logback.xml'));
    }

    static void main(String[] args) {
        RenderServer rs = new RenderServer()
        rs.configureLogging()
        rs.resetRunningTasks()
        rs.startThreads(rs.conf.getThreadCount(), rs.conf.getSleepDuration(), false)
    }

}
