package eu.renderserver;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Dummy Shutdown class for procrun' --StopClass parameter.
 * http://commons.apache.org/daemon/procrun.html
 *
 */
public class Shutdown {

    static Logger log = LoggerFactory.getLogger(Shutdown.class);

    public static void main(String[] args){
        log.debug("Shutdown class has been called.");
    }

}
