package eu.renderserver

import safran.Client
import org.slf4j.LoggerFactory
import org.slf4j.Logger
import server.global.Constants

import utils.ParamParser

/**
 *
 */
class RenderTask {

    Logger log = LoggerFactory.getLogger(this.class)

    Client client
    Long id
    String name
    Config config

    void execute() {
        String cmd = config.getCommand(name)
        if (cmd == null || cmd.trim().length() == 0) {
            log.debug("Could not find command for '${name}' - ignoring task.")
            return;
        }
        
        // connect anew - each RenderTask must have its own session, in case some other RT fails
        // and (accidentally) disconnects / somehow breaks the session (connection problem, timeouts).
        client.connect();
        try {
            client.lock(id)
        }
        catch (RuntimeException e) {
            log.debug("Failed to lock render task, maybe some other process is working on it. Will try again later, if necessary.")
            return
        }

        // check state - must be new
        log.debug("Check lifeCycleState if status is still new")
        if (!getLifeCycleStateName(id)?.equals(Constants.RENDERSERVER_RENDER_TASK_NEW)) {
            log.debug("LifeCycleState is not 'new'. Step down.")
            client.unlock(id)
            return
        }

        // set state to rendering
        log.debug("Setting lifecycleState of object to: " + Constants.RENDERSERVER_RENDER_TASK_RENDERING)
        client.changeLifeCycleState(id, Constants.RENDERSERVER_RENDER_TASK_RENDERING)
        client.unlock(id)

        // fetch object to get owner (for sudo)
        String rt = client.getObject(id)
        Long ownerId = Client.parseLongNode(rt, "/objects/object/owner/id")
        String user = ParamParser.parseXmlToDocument(rt).selectSingleNode("/objects/object/owner/name").text
        if (user == client.username) {
//            client.forkSession()
            log.debug("no need to sudo - user is owner.")
        }
        else {
            String sudoTicket = client.sudo(ownerId)
            client.setSessionTicket(
                    ParamParser.parseXmlToDocument(sudoTicket).selectSingleNode("/sudoTicket").text)

        }

        String host = client.host
        if(config.requiresEscapedUrlForCinnamonClient(name)){
            host = host.replaceAll('/', '//')
            // log.debug("escape URL for cinnamon client: $host")
        }

        def replacements = ['__ticket__': client.sessionTicket,
                '__id__': id.toString(),
                '__repository__': client.repository,
                '__host__': host,
                '__user__': client.username
        ]

        replacements.each {k, v ->
            cmd = cmd.replace(k, v)
        }

        log.debug("Will now execute: " + cmd)
        Process process = cmd.execute()
        process.in.eachLine {line ->
            log.debug(line)
        }
        log.debug("cmd was executed.")
        client.disconnect()
        log.debug("client was disconnected.")
    }

    String getLifeCycleStateName(Long id) {
        String osd = client.getObject(id)
        Long lcsId
        try {
            lcsId = Client.parseLongNode(osd, "//lifeCycleState")
        }
        catch (RuntimeException e) {
            log.debug("getLCS-name", e)
            return null
        }
        String lcs = client.getLifeCycleState(lcsId)
        def name = ParamParser.parseXmlToDocument(lcs).selectSingleNode("//sysName")
        log.debug("sysName: ${name.getText()}")
        return name?.getText()
    }

}
