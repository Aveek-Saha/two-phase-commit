import org.json.JSONObject;

import java.io.IOException;
import java.rmi.RemoteException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import com.example.server.*;
import com.example.server.Status;

import io.grpc.*;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

/**
 * A class representing the coordinator for the two phase commit protocol
 */
public class Coordinator {

    /**
     * A constructor for the coordinator that initializes the list of replicas and other variables
     * required for managing those replicas
     */
    //public Coordinator() throws IOException, InterruptedException {
    //    //this.start(port);
    //    //this.blockUntilShutdown();
    //}

    private Server server;

    public void start(int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new CoordinatorService())
                .build()
                .start();
        ServerLogger.log("Server started, listening on " + port);
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                System.err.println("*** shutting down gRPC server since JVM is shutting down");
                try {
                    Coordinator.this.stop();
                } catch (InterruptedException e) {
                    e.printStackTrace(System.err);
                }
                System.err.println("*** server shut down");
            }
        });
    }

    private void stop() throws InterruptedException {
        if (server != null) {
            server.shutdown().awaitTermination(30, TimeUnit.SECONDS);
        }
    }

    /**
     * Await termination on the main thread since the grpc library uses daemon threads.
     */
    public void blockUntilShutdown() throws InterruptedException {
        if (server != null) {
            server.awaitTermination();
        }
    }

    private class CoordinatorService extends ServiceGrpc.ServiceImplBase {
        private final List<ReplicaServer> replicas;
        private final ExecutorService executor;
        private final ScheduledExecutorService scheduler;
        private final ConcurrentHashMap<ReplicaServer, ScheduledFuture<?>> heartbeats;
        private final long ACK_TIMEOUT = 5000;
        private final int maxRetries = 3;
        private final long initialDelayMillis = 1000; // Initial delay in milliseconds
        private final long HEARTBEAT_TIMEOUT = 3000; // Maximum delay in milliseconds

        CoordinatorService () {
            this.replicas = new ArrayList<>();
            this.executor = Executors.newCachedThreadPool();
            this.scheduler = Executors.newScheduledThreadPool(5);
            this.heartbeats = new ConcurrentHashMap<>();
        }

        /**
         * Adds a replica to the list of available replicas and adds the replica name to the name list
         *
         * @param replica the replica to add to the replica list
         */
        @Override
        public void addReplica (ReplicaServer replica, StreamObserver<Status> responseObserver) {
            this.replicas.add(replica);
            String clientName = replica.getHostname();

            ServerLogger.log("Added new replica: " + clientName);
            this.startHeartbeat(replica);
            ServerLogger.logInfo("Started heartbeat on replica: " + clientName);

            responseObserver.onCompleted();
        }

        /**
         * Starts a transaction using the Two phase commit protocol when called by a replica
         *
         * @param request the request sent by the replica in string form
         * @param responseObserver the result of the transaction
         */
        @Override
        public void startTransaction(Request request, StreamObserver<Response> responseObserver) {
            Response response;
            String message;

            if (this.prepare(request)) {
                Response commitResult = this.commit(request);
                if (commitResult != null) {
                    ServerLogger.log("Successfully committed to all replicas");
                    responseObserver.onNext(commitResult);
                    return;
                } else {
                    message = "Operation failed. Could not commit transaction to all replicas";
                    ServerLogger.logError("Failed to commit to all replicas");
                }
            } else {
                if (this.abort(request)) {
                    message = "Operation failed. Transaction successfully aborted";
                    ServerLogger.log("Successfully aborted transaction");
                } else {
                    message = "Operation failed. Transaction could not be aborted";
                    ServerLogger.logError("Failed to abort transaction");
                }
            }

            response = Response.newBuilder().setMsg(message).setStatus("400").build();
            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * Starts a heartbeat on a replica. Sends a heartbeat signal at regular intervals using
         * exponential backoff until the max number of retries is reached
         *
         * @param replica the replica to start the heartbeat on
         */
        public void startHeartbeat(ReplicaServer replica) {
            Runnable heartbeatTask = () -> {
                String replicaHost = replica.getHostname();
                ManagedChannel channel = Grpc.newChannelBuilder(getReplica(replica),
                        InsecureChannelCredentials.create()).build();
                for (int attempt = 1; attempt <= maxRetries; attempt++) {
                    try {
                        Ping ping = Ping.newBuilder().build();
                        Status res = ServiceGrpc.newBlockingStub(channel).isAlive(ping);
                        if (res.getSuccess()) {
                            return;
                        } else {
                            ServerLogger.logWarning("Replica '" + replicaHost + "' is not responding" +
                                    ". Retrying attempt " + attempt);
                        }
                        // Exponential backoff
                        long delay = Math.min(initialDelayMillis * (1 << attempt), HEARTBEAT_TIMEOUT);
                        Thread.sleep(delay);
                    } catch (Exception e) {
                        ServerLogger.logWarning(
                                "Error while checking heartbeat of replica '" + replicaHost +
                                        "'. Retrying attempt " + attempt + ": " + e.getMessage());
                    }
                }
                ServerLogger.logError("Max retries reached for heartbeat. Replica unresponsive");
                this.removeReplica(replica);
            };
            ScheduledFuture<?> future =
                    this.scheduler.scheduleWithFixedDelay(heartbeatTask, initialDelayMillis,
                            initialDelayMillis, TimeUnit.MILLISECONDS);

            heartbeats.put(replica, future);
        }

        /**
         * Removes an unresponsive replica from the list of replicas
         *
         * @param replica the replica to be removed from the list
         */
        public void removeReplica(ReplicaServer replica) {
            ScheduledFuture<?> future = heartbeats.get(replica);

            if (!future.isDone()) {
                this.replicas.remove(replica);
                String clientName = replica.getHostname();
                this.scheduler.schedule(() -> future.cancel(true), 0, TimeUnit.MILLISECONDS);
                ServerLogger.log(
                        "Removed unresponsive replica '" + clientName + "' and stopped " + "heartbeat");
            }
        }

        /**
         * Asks all the replicas if they are prepared to commit
         *
         * @param request the transaction to prepare
         * @return true if all the replicas successfully prepared for the commit and false if not
         */
        public boolean prepare(Request request) {
            List<Callable<Boolean>> prepareTasks = new ArrayList<>();

            ServerLogger.logInfo("Add prepare tasks for all replicas");
            for (ReplicaServer replica : this.replicas) {
                prepareTasks.add(() -> {
                    ManagedChannel channel = Grpc.newChannelBuilder(getReplica(replica),
                            InsecureChannelCredentials.create()).build();
                    Status res = ServiceGrpc.newBlockingStub(channel).prepare(request);
                    return res.getSuccess();
                });
            }

            try {
                ServerLogger.logInfo("Send prepare requests to all replicas");
                List<Future<Boolean>> futures =
                        executor.invokeAll(prepareTasks, ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                for (Future<Boolean> future : futures) {
                    try {
                        // Wait for task completion and check for exceptions
                        boolean result = future.get(ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (!result) {
                            return false;
                        }
                    } catch (Exception e) {
                        // Handle exception (e.g., task failed)
                        ServerLogger.logError("Could not send prepare requests: " + e.getMessage());
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                ServerLogger.logError("Error while preparing: " + e.getMessage());
                return false;
            }

            ServerLogger.log("All replicas are prepared");
            return true;
        }

        /**
         * Asks all the replicas to commit a transaction
         *
         * @param request the transaction to commit
         * @return the result of the transaction from the replicas
         */
        public Response commit(Request request) {
            List<Callable<Response>> commitTasks = new ArrayList<>();
            Response commitResponse;
            ServerLogger.logInfo("Add commit tasks for all replicas");

            for (ReplicaServer replica : this.replicas) {
                commitTasks.add(() -> {
                    ManagedChannel channel = Grpc.newChannelBuilder(getReplica(replica),
                            InsecureChannelCredentials.create()).build();
                    return ServiceGrpc.newBlockingStub(channel).commit(request);
                });
            }

            try {
                ServerLogger.logInfo("Send commit requests to all replicas");
                List<Future<Response>> futures =
                        executor.invokeAll(commitTasks, ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                List<Response> responses = new ArrayList<>();
                for (Future<Response> future : futures) {
                    try {
                        // Wait for task completion and check for exceptions
                        Response response = future.get(ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (response == null) {
                            return null;
                        }
                        responses.add(response);
                    } catch (Exception e) {
                        // Handle exception (e.g., task failed)
                        ServerLogger.logError("Could not send commit requests: " + e.getMessage());
                        return null;
                    }
                }
                // Get all the responses from the replicas and log them
                for (Response response : responses) {
                    String status = response.getStatus();
                    String message = response.getMsg();
                    if (status.equalsIgnoreCase("400")) {
                        ServerLogger.logError(message);
                    } else {
                        ServerLogger.log(message);
                    }
                }
                commitResponse = responses.get(0);
            } catch (InterruptedException e) {
                ServerLogger.logError("Error while committing: " + e.getMessage());
                return null;
            }

            return commitResponse;
        }

        /**
         * Asks all the replicas to abort a transaction
         *
         * @return true if all replicas abort successfully and false if not
         */
        public boolean abort(Request request) {
            List<Callable<Boolean>> abortTasks = new ArrayList<>();

            ServerLogger.logWarning("Add abort tasks for all replicas");
            for (ReplicaServer replica : this.replicas) {
                abortTasks.add(() -> {
                    ManagedChannel channel = Grpc.newChannelBuilder(getReplica(replica),
                            InsecureChannelCredentials.create()).build();
                    Status res = ServiceGrpc.newBlockingStub(channel).abort(request);
                    return res.getSuccess();
                });
            }

            try {
                List<Future<Boolean>> futures =
                        executor.invokeAll(abortTasks, ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                for (Future<Boolean> future : futures) {
                    try {
                        // Wait for task completion and check for exceptions
                        boolean result = future.get(ACK_TIMEOUT, TimeUnit.MILLISECONDS);
                        if (!result) {
                            return false;
                        }
                    } catch (Exception e) {
                        // Handle exception (e.g., task failed)
                        ServerLogger.logError("Could not send abort requests: " + e.getMessage());
                        return false;
                    }
                }
            } catch (InterruptedException e) {
                ServerLogger.logError("Error during abort: " + e.getMessage());
                return false;
            }

            return true;
        }
    }

    public String getReplica(ReplicaServer replica) {
        String replicaHost = replica.getHostname();
        int replicaPort = replica.getPort();
        return replicaHost + replicaPort;
    }
}
