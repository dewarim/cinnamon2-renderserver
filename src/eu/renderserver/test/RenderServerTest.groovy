package eu.renderserver.test

import org.testng.annotations.*

import safran.Client
import eu.renderserver.RenderServer
import eu.renderserver.Config
import safran.setup.BasicDatabaseSetup
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import eu.renderserver.RenderTask

import server.global.Constants

/**
 * Tests for the Cinnamon RenderServer.
 * Creates render tasks for the (experimental) foo2pdf test and runs them.
 * Does not yet test startup of render server thread with existing running render tasks.
 */
class RenderServerTest {

    Logger log = LoggerFactory.getLogger(this.class)

    Client client
    RenderServer rs

    @BeforeTest
    void setupClient() {
        def configPath = System.getProperty("RENDERSERVER_DIR", ".")
        def clientConfigFile = new File(configPath, "renderserver.client.properties")
        Properties props = new Properties()
        BufferedInputStream bis = new BufferedInputStream(new FileInputStream(
                clientConfigFile));
        props.load(bis);
        bis.close();
        client = new Client(props)

        BasicDatabaseSetup bds = new BasicDatabaseSetup(client, props)
        assert bds.initializeDatabase() : "Could not initialize database."
        assert client.connect() : "Could not connect with client ${client}"

//        createRenderServerLifeCycle()

        rs = new RenderServer()
    }

    // happens in Initializer now.
//    void createRenderServerLifeCycle(){
//        // create Lifecycle.
//        Long renderLc = client.createLifeCycle(Constants.RENDER_SERVER_LIFECYCLE);
//
//        // create LifeCycleState
//        // new is last element in lcList so it becomes the default for the LC:
//        def lcList = [
//                Constants.RENDERSERVER_RENDER_TASK_FAILED,
//                Constants.RENDERSERVER_RENDER_TASK_FINISHED,
//                Constants.RENDERSERVER_RENDER_TASK_RENDERING,
//                Constants.RENDERSERVER_RENDER_TASK_NEW
//                ]
//        def states = [:]
//        lcList.each{name ->
//            Long state =  client.addLifeCycleState(renderLc, name, null, true, "server.lifecycle.state.NopState", null)
//            states.put(name,state)
//        }
//    }


    @Test
    void configTest(){
        Config config = rs.loadConfig()
        assert config.getClients().size() > 0 : 'Looks like the configuration file does not contain any repositories - client list is empty.'

        assert config.getCommand("foo2pdf").equals("java -jar lib/foo2pdf.jar __id__ __repository__") : "Could not find the foo2pdf render task."
    }

//    @Test
    void serverTest(){

        Long folderId = client.createFolder("RenderFolder", 0L)

        Long sourceId = client.create("<meta/>", "source OpenOffice object for rendering", "testdata/lgpl.odt","odt", folderId )
        File content = client.getContentAsFile(sourceId);
        assert content.length() == 37065 : "Content is not 37065 bytes as expected: "+content.length();

        String metadata = "<metaset type=\"render_input\"><sourceId>"+ sourceId +"</sourceId><renderTaskName>foo2pdf</renderTaskName></metaset>"
        Long id = client.startRenderTask(folderId, metadata)
        log.debug("renderTaskId: "+id)

        rs.renderTasks = rs.findNewTasks()
        assert rs.renderTasks.size() == 1 : 'Failed to create test RenderTask.'
        RenderTask renderTask = rs.renderTasks.collect{it}.get(0)
        renderTask.execute()

        File pdf = client.getContentAsFile(id)
        assert pdf.length() == 6502 : "Size of PDF is not 6502 but: "+pdf.length();

        rs.renderTasks = rs.findNewTasks()
        assert rs.renderTasks.size() == 0 : 'The # of RenderTasks found is not 0: '+rs.renderTasks.size()

        // now try with MS-Word document
        Long wordDocId = client.create("<meta/>", "source ms-word object for rendering", "testdata/lgpl.doc","doc", folderId )
        String wordMeta =  "<metaset type=\"render_input\"><sourceId>"+ wordDocId +"</sourceId><renderTaskName>foo2pdf</renderTaskName></metaset>"
        Long wordTaskId = client.startRenderTask(folderId, wordMeta)
        RenderTask wordRenderTask = rs.findNewTasks().collect{it}.get(0)
        wordRenderTask.execute()
        File wordPdf = client.getContentAsFile(wordTaskId)
        assert wordPdf.length() == 5450 : "Size of PDF is not 6502 but: "+wordPdf.length();

        // ODT with embedded image
        // currently, the image is not detected, so let's skip this test.
//        Long imageDocId = client.create("<meta/>", "source odt object with cinnamon.jpg", "testdata/cinnamon.odt","odt", folderId )
//        String imageDocMeta =  "<metaset type=\"render_input\"><sourceId>"+ imageDocId +"</sourceId><renderTaskName>foo2pdf</renderTaskName></metaset>"
//        Long taskId = client.startRenderTask(folderId, imageDocMeta)
//        RenderTask imageDocTask = rs.findNewTasks().collect{it}.get(0)
//        imageDocTask.execute()
//        File taskPdf = client.getContentAsFile(taskId)
//        assert taskPdf.length() == 1269 : "Size of PDF is not 6502 but: "+taskPdf.length();
    }

//    @Test(dependsOnMethods=["serverTest"])
    /**
     * brokenRendererTest will try to start a render task for a broken odt file.
     * Expected behavior: Foo2PDF will detect brokenness and add StackTrace to render_output metaset.
     */
    public void brokenRendererTest(){
        Long folderId = client.createFolder("RenderFolder-broken", 0L)

        Long sourceId = client.create("<meta/>", "source OpenOffice object for rendering", "testdata/lgpl.broken.odt","odt", folderId )
        String metadata = "<metaset type=\"render_input\"><sourceId>"+ sourceId +"</sourceId><renderTaskName>foo2pdf</renderTaskName></metaset>"
        Long id = client.startRenderTask(folderId, metadata)
        log.debug("renderTaskId: "+id)

        rs.renderTasks = rs.findNewTasks()
        assert rs.renderTasks.size() == 1 : 'Failed to create test RenderTask.'
        RenderTask renderTask = (RenderTask) rs.renderTasks.collect{it}.get(0)
        renderTask.execute()

        assert client.getMeta(id).contains("java.io.EOFException") : "RenderTask did not produce the expected error node.\n${client.getMeta(id)}"
        client.delete(id)
    }

//    @Test(dependsOnMethods=["serverTest"])
    /**
     * brokenRendererTest will create a broken render task and check if the RenderServer can handle that.
     * Expected behavior: error message in /meta/metaset[@type=render-output]
     */
    public void brokenRenderTaskTest(){
        Long folderId = client.createFolder("RenderFolder-brokenTask", 0L)

        String metadata = "<metaset type=\"render_input\"><sourceId>0</sourceId><renderTaskName>foo2bar</renderTaskName></metaset>"
        Long id = client.startRenderTask(folderId, metadata)
        log.debug("renderTaskId: "+id)

        rs.renderTasks = rs.findNewTasks()
        assert rs.renderTasks.size() == 1 : "Failed to create test RenderTask - found ${rs.renderTasks.size()} instead of 1"
        RenderTask renderTask = (RenderTask) rs.renderTasks.collect{it}.get(0)
        rs.executeTasks()

        assert client.getMeta(id).contains("RuntimeException") : "RenderTask did not produce the expected error node.\n${client.getMeta(id)}"
        client.delete(id) // cleanup before the next task finds this.
    }

    @Test()
    /**
     * NOTE: This test will currently run forever.
     */
    public void multithreadingRenderTest(){
        Long folderId = client.createFolder("RenderFolder-Multithreading", 0L)

        // create 10 tasks
        (0..10).each{
            Long sourceId = client.create("<meta/>", "source OpenOffice object for rendering", "testdata/lgpl.odt","odt", folderId )
            String metadata = "<metaset type=\"render_input\"><sourceId>"+ sourceId +"</sourceId><renderTaskName>foo2pdf</renderTaskName></metaset>"
            Long id = client.startRenderTask(folderId, metadata)
        }
        // start 5 threads with 1s time between them
        rs.startThreads(5, 1000, true)

        log.debug("finished multithreaded test")
    }
}
