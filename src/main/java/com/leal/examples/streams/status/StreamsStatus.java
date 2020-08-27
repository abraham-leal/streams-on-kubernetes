package com.leal.examples.streams.status;

import org.apache.kafka.streams.KafkaStreams;
import org.apache.kafka.streams.processor.ThreadMetadata;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.servlet.ServletContextHandler;
import org.eclipse.jetty.servlet.ServletHolder;
import org.glassfish.jersey.server.ResourceConfig;
import org.glassfish.jersey.servlet.ServletContainer;
import org.apache.log4j.Logger;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.core.Response;
import java.util.Set;

@Path("streams-check")
public class StreamsStatus {

    KafkaStreams app;
    private final int port;
    private Server jettyServer;
    private Logger logger = Logger.getLogger(StreamsStatus.class);

    public StreamsStatus(KafkaStreams app){
        this(app,7000);
    }

    public StreamsStatus(KafkaStreams app, int port){
        this.app = app;
        this.port = port;
    }

    public void start(){
        maybeStartServer();
    }

    public void stop() {
        try {
            if ( jettyServer != null) {
                jettyServer.stop();
            }
        } catch ( Exception e ) {
            logger.info("No jetty server to stop");
            e.printStackTrace();
        }

    }

    private void maybeStartServer (){
        try {
            ServletContextHandler context = new ServletContextHandler(ServletContextHandler.SESSIONS);
            context.setContextPath("/");

            ResourceConfig rc = new ResourceConfig();
            rc.register(this);

            ServletContainer sc = new ServletContainer(rc);
            ServletHolder holder = new ServletHolder(sc);
            context.addServlet(holder, "/*");

            jettyServer = new Server(this.port);
            jettyServer.setHandler(context);

            logger.info("Starting jetty server");
            jettyServer.start();
        } catch (Exception e) {
            logger.info("Server for status could not be started, letting the application continue...");
            e.printStackTrace();
        }
    }

    /**
    We determine liveness through two means: The state of the application as a whole and the state of each stream thread.
    All threads must be running for us to determine our streams application as alive and well.
    Otherwise, we'll want to restart the application to restart the threads.
     */
    @GET
    @Path("/liveness")
    public Response getHealth(){

        Response check = Response.serverError().build();

        if (this.app.state().isRunningOrRebalancing()) {
            Set<ThreadMetadata> myThreadsStatus = this.app.localThreadsMetadata();
            check = Response.ok().build();
            logger.debug("Streams app is running");
            for (ThreadMetadata x : myThreadsStatus){
                if (!(x.threadState().equalsIgnoreCase("RUNNING") ||
                x.threadState().equalsIgnoreCase("STARTING") ||
                x.threadState().equalsIgnoreCase("PARTITIONS_REVOKED") ||
                x.threadState().equalsIgnoreCase("PARTITIONS_ASSIGNED"))){
                    logger.debug("At least one thread in the Streams app is dead, returning bad response.");
                    check = Response.serverError().build();
                }
            }
        }
        return check;
    }

    /**
    We determine readiness through the internal state stores. Kafka Streams commonly does not need to serve requests to
    other clients. However, this probe might be helpful when interactive queries are enabled.
     */
    @GET
    @Path("/readiness")
    public Response getReadiness() {
        Response check = Response.serverError().build();

        for (ThreadMetadata thread : this.app.localThreadsMetadata()) {
            if (!thread.threadState().equalsIgnoreCase("RUNNING")){
                logger.debug("At least one thread in the Streams app is not running, returning bad response.");
                return check;
            }
        }

        check = Response.ok().build();
        return check;
    }
}
