import org.json.JSONObject;

import java.rmi.ConnectException;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
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

/**
 * A class representing the coordinator for the two phase commit protocol
 */
public class Coordinator implements CoordinatorInterface {
    private final List<ReplicaInterface> replicas;
    private final ExecutorService executor;
    private final ScheduledExecutorService scheduler;
    private final ConcurrentHashMap<ReplicaInterface, ScheduledFuture<?>> heartbeats;
    private final ConcurrentHashMap<ReplicaInterface, String> replicaNames;
    private final long ACK_TIMEOUT = 5000;
    private final int maxRetries = 3;
    private final long initialDelayMillis = 1000; // Initial delay in milliseconds
    private final long HEARTBEAT_TIMEOUT = 3000; // Maximum delay in milliseconds

    /**
     * A constructor for the coordinator that initializes the list of replicas and other variables
     * required for managing those replicas
     */
    public Coordinator() {
        this.replicas = new ArrayList<>();
        this.executor = Executors.newCachedThreadPool();
        this.scheduler = Executors.newScheduledThreadPool(5);
        this.heartbeats = new ConcurrentHashMap<>();
        this.replicaNames = new ConcurrentHashMap<>();
    }

    /**
     * Starts a heartbeat on a replica. Sends a heartbeat signal at regular intervals using
     * exponential backoff until the max number of retries is reached
     *
     * @param replica the replica to start the heartbeat on
     */
    public void startHeartbeat(ReplicaInterface replica) {
        Runnable heartbeatTask = () -> {
            String replicaName = this.replicaNames.get(replica);
            for (int attempt = 1; attempt <= maxRetries; attempt++) {
                try {
                    if (replica.isAlive()) {
                        return;
                    } else {
                        ServerLogger.logWarning("Replica '" + replicaName + "' is not responding" +
                                ". Retrying attempt " + attempt);
                    }
                    // Exponential backoff
                    long delay = Math.min(initialDelayMillis * (1 << attempt), HEARTBEAT_TIMEOUT);
                    Thread.sleep(delay);
                } catch (ConnectException e) {
                    ServerLogger.logWarning("Replica '" + replicaName + "' is not responding. " +
                            "Retrying attempt " + attempt);
                } catch (Exception e) {
                    ServerLogger.logWarning(
                            "Error while checking heartbeat of replica '" + replicaName +
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
     * Adds a replica to the list of available replicas and adds the replica name to the name list
     *
     * @param replica the replica to add to the replica list
     */
    public void addReplica(ReplicaInterface replica) {
        this.replicas.add(replica);
        String clientName = "unknown";
        try {
            clientName = RemoteServer.getClientHost();
        } catch (ServerNotActiveException e) {
            ServerLogger.logError("Error getting client info: " + e.getMessage());
        }

        this.replicaNames.put(replica, clientName);
        ServerLogger.log("Added new replica: " + clientName);
        this.startHeartbeat(replica);
        ServerLogger.logInfo("Started heartbeat on replica: " + clientName);

    }

    /**
     * Removes an unresponsive replica from the list of replicas
     *
     * @param replica the replica to be removed from the list
     */
    public void removeReplica(ReplicaInterface replica) {
        ScheduledFuture<?> future = heartbeats.get(replica);

        if (!future.isDone()) {
            this.replicas.remove(replica);
            String clientName = this.replicaNames.remove(replica);
            this.scheduler.schedule(() -> future.cancel(true), 0, TimeUnit.MILLISECONDS);
            ServerLogger.log(
                    "Removed unresponsive replica '" + clientName + "' and stopped " + "heartbeat");
        }
    }

    /**
     * Starts a transaction using the Two phase commit protocol when called by a replica
     *
     * @param reqString the request sent by the replica in string form
     * @return the result of the transaction as a string
     * @throws RemoteException If there is an error during the remote call
     */
    public String startTransaction(String reqString) throws RemoteException {
        JSONObject response = new JSONObject();
        String message;

        if (this.prepare(reqString)) {
            String commitResult = this.commit(reqString);
            if (commitResult != null) {
                ServerLogger.log("Successfully committed to all replicas");
                return commitResult;
            } else {
                message = "Operation failed. Could not commit transaction to all replicas";
                ServerLogger.logError("Failed to commit to all replicas");
            }
        } else {
            if (this.abort()) {
                message = "Operation failed. Transaction successfully aborted";
                ServerLogger.log("Successfully aborted transaction");
            } else {
                message = "Operation failed. Transaction could not be aborted";
                ServerLogger.logError("Failed to abort transaction");
            }
        }

        response.put("message", message);
        response.put("status", "400");

        return response.toString();
    }

    /**
     * Asks all the replicas if they are prepared to commit
     *
     * @param request the transaction to prepare
     * @return true if all the replicas successfully prepared for the commit and false if not
     */
    public boolean prepare(String request) {
        List<Callable<Boolean>> prepareTasks = new ArrayList<>();

        ServerLogger.logInfo("Add prepare tasks for all replicas");
        for (ReplicaInterface replica : this.replicas) {
            prepareTasks.add(() -> {
                try {
                    return replica.prepare(request);
                } catch (RemoteException e) {
                    // Handle RemoteException
                    ServerLogger.logError("Could not add prepare tasks: " + e.getMessage());
                    return false;
                }
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
    public String commit(String request) {
        List<Callable<String>> commitTasks = new ArrayList<>();
        String commitResponse;
        ServerLogger.logInfo("Add commit tasks for all replicas");

        for (ReplicaInterface replica : this.replicas) {
            commitTasks.add(() -> {
                try {
                    return replica.commit(request);
                } catch (RemoteException e) {
                    // Handle RemoteException
                    ServerLogger.logError("Could not add commit tasks: " + e.getMessage());
                    return null;
                }
            });
        }

        try {
            ServerLogger.logInfo("Send commit requests to all replicas");
            List<Future<String>> futures =
                    executor.invokeAll(commitTasks, ACK_TIMEOUT, TimeUnit.MILLISECONDS);
            List<String> responses = new ArrayList<>();
            for (Future<String> future : futures) {
                try {
                    // Wait for task completion and check for exceptions
                    String response = future.get(ACK_TIMEOUT, TimeUnit.MILLISECONDS);
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
            for (String resString : responses) {
                JSONObject response = new JSONObject(resString);
                String status = response.getString("status");
                String message = response.getString("message");
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
    public boolean abort() {
        List<Callable<Boolean>> abortTasks = new ArrayList<>();

        ServerLogger.logWarning("Add abort tasks for all replicas");
        for (ReplicaInterface replica : this.replicas) {
            abortTasks.add(() -> {
                try {
                    return replica.abort();
                } catch (RemoteException e) {
                    // Handle RemoteException
                    ServerLogger.logError("Could not add abort tasks: " + e.getMessage());
                    return false;
                }
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
