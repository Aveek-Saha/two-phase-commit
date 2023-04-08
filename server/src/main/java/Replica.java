import com.example.server.Ping;
import com.example.server.ReplicaServer;
import com.example.server.Request;
import com.example.server.Response;
import com.example.server.ServiceGrpc;
import com.example.server.Status;

import java.io.IOException;
import java.net.InetAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.InsecureServerCredentials;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.stub.StreamObserver;

public class Replica {
    private Server server;
    private ServiceGrpc.ServiceBlockingStub coordinatorStub;

    /**
     * Initialises the server with a key value store and a lock
     */

    public void start(String coordinator, int port) throws IOException {
        server = Grpc.newServerBuilderForPort(port, InsecureServerCredentials.create())
                .addService(new ReplicaService(coordinator)).build().start();
        ServerLogger.log("Server started, listening on " + port);

        ManagedChannel coordinatorChannel =
                Grpc.newChannelBuilder(coordinator, InsecureChannelCredentials.create()).build();
        coordinatorStub = ServiceGrpc.newBlockingStub(coordinatorChannel);

        ReplicaServer replicaServer = ReplicaServer.newBuilder().setPort(port)
                .setHostname(InetAddress.getLocalHost().getHostAddress()).build();
        Status response = coordinatorStub.addReplica(replicaServer);
        if (response.getSuccess()) ServerLogger.log("Connected to coordinator");
        else ServerLogger.logError("Failed to connect to coordinator");

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                // Use stderr here since the logger may have been reset by its JVM shutdown hook.
                ServerLogger.logError("Shutting down gRPC server since JVM is shutting down");
                try {
                    Replica.this.stop();
                } catch (InterruptedException e) {
                    ServerLogger.logError("Shutting down gRPC server since JVM is shutting down");
                }
                ServerLogger.logError("Server shut down");
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

    public class ReplicaService extends ServiceGrpc.ServiceImplBase {
        private final Lock lock;
        KeyValue kvs;
        String coordinator;

        ReplicaService(String coordinator) {
            kvs = new KeyValue();
            lock = new ReentrantLock();
            this.coordinator = coordinator;
        }

        /**
         * Called by the coordinator when the server needs to prepare for a commit
         *
         * @param request the request to commit
         */
        @Override
        public void prepare(Request request, StreamObserver<Status> responseObserver) {
            boolean success = false;
            if (this.lock.tryLock()) {
                ServerLogger.logInfo("Acquired lock for prepare");
                success = true;
            } else {
                ServerLogger.logWarning("Could not acquire lock for prepare");
            }
            responseObserver.onNext(Status.newBuilder().setSuccess(success).build());

        }

        /**
         * Called by the coordinator when the server needs to commit a transaction
         *
         * @param request          the request to commit
         * @param responseObserver the response observer
         */
        @Override
        public void commit(Request request, StreamObserver<Response> responseObserver) {
            try {
                ServerLogger.logInfo(
                        "Received request from coordinator to commit: " + request.toString());

                Response response;
                String method = request.getOperation();
                // Twp phase commit only required for put and delete since they modify the KV store
                switch (method.toUpperCase()) {
                    case "PUT":
                        response = handlePut(request);
                        break;
                    case "DEL":
                        response = handleDelete(request);
                        break;
                    default:
                        response = Response.newBuilder().setStatus("400")
                                .setMsg("Invalid commit request").build();
                        break;
                }
                ServerLogger.logInfo(
                        "Sent response to coordinator for commit: " + request);
                responseObserver.onNext(response);
            } finally {
                try {
                    this.lock.unlock();
                    ServerLogger.logInfo("Unlocked after commit");
                } catch (IllegalMonitorStateException e) {
                    ServerLogger.logWarning("Could not unlock after commit: " + e.getMessage());
                }
            }
        }

        /**
         * Called by the coordinator when a transaction has to be aborted
         */
        @Override
        public void abort(Request request, StreamObserver<Status> responseObserver) {
            boolean success = false;
            try {
                this.lock.unlock();
                ServerLogger.logInfo("Unlocked during abort");
                success = true;
            } catch (IllegalMonitorStateException e) {
                ServerLogger.logWarning("Could not unlock during abort: " + e.getMessage());
            }
            responseObserver.onNext(Status.newBuilder().setSuccess(success).build());
        }

        /**
         * Called by the coordinator to check if the server is alive
         *
         * @param ping             pings the server
         * @param responseObserver true if the server is alive
         */
        @Override
        public void isAlive(Ping ping, StreamObserver<Status> responseObserver) {
            responseObserver.onNext(Status.newBuilder().setSuccess(true).build());
        }

        /**
         * Takes in an input request from the client and returns the appropriate response
         *
         * @param request          The formatted request as a string
         * @param responseObserver The response to be sent to the client
         */
        @Override
        public void generateResponse(Request request, StreamObserver<Response> responseObserver) {
            Response response;
            String clientName = "unknown";

            ServerLogger.log("Received request from " + clientName + ": " + request);

            // Process request
            String method = request.getOperation();

            switch (method.toUpperCase()) {
                case "GET":
                    String getKey = request.getKey();
                    response = handleGet(getKey);
                    break;
                case "PUT":
                case "DEL":
                    response = coordinatorStub.startTransaction(request);
                    break;
                default:
                    response = Response.newBuilder()
                            .setMsg("Invalid method. Valid methods are " + "GET, PUT and DEL")
                            .setStatus("400").build();
                    break;
            }
            ServerLogger.log("Sent response to " + clientName + ": " + response);

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        /**
         * Handles getting the corresponding value for a key from the KV store if it exists.
         *
         * @param key the key to be inserted
         * @return the message to return to the client along with any data as a JSON string
         */
        public Response handleGet(String key) {
            String value = kvs.get(key);
            String message;
            String status;

            // If the key actually exists return the corresponding value
            if (value != null) {
                ServerLogger.log("Successful GET on key '" + key + "' with value '" + value + "'");
                message = "Got key '" + key + "' with value '" + value + "'";
                status = "200";
            } else {
                ServerLogger.logError("Could not find key '" + key + "'");
                message = "GET FAILED for key '" + key + "'";
                status = "400";
            }
            return Response.newBuilder().setStatus(status).setMsg(message).build();
        }

        /**
         * Called by the coordinator when the server needs to commit a transaction
         *
         * @param data the data to insert
         * @return the response message after the commit was completed
         */
        private Response handlePut(Request data) {
            this.lock.lock();
            try {
                String key = data.getKey();
                String value = data.getValue();
                String message;
                String status;

                // Return a success if the key was successfully put into the KV store
                if (kvs.put(key, value)) {
                    ServerLogger.log(
                            "Successful PUT on key '" + key + "' with value '" + value + "'");
                    message = "Put key '" + key + "' with value '" + value + "'";
                    status = "200";
                } else {
                    ServerLogger.logError("Could not PUT key '" + key + "'");
                    message = "PUT FAILED for key '" + key + "' with value '" + value + "'";
                    status = "400";
                }
                return Response.newBuilder().setStatus(status).setMsg(message).build();
            } finally {
                this.lock.unlock();
            }
        }

        /**
         * Handles deleting a key value pair from the KV store
         *
         * @param data the data to delete from the KV store
         * @return the message to return to the client along with any data as a JSON string
         */
        private Response handleDelete(Request data) {
            this.lock.lock();
            try {
                String key = data.getKey();
                String message;
                String status;

                // If the key exists and was deleted successfully return a success
                if (kvs.delete(key)) {
                    ServerLogger.log("Successful DEL on key '" + key + "'");
                    message = "Deleted key '" + key + "'";
                    status = "200";
                } else {
                    ServerLogger.logError("Could not DEL key '" + key + "'");
                    message = "DEL FAILED for key '" + key + "'";
                    status = "400";
                }
                return Response.newBuilder().setStatus(status).setMsg(message).build();
            } finally {
                this.lock.unlock();
            }
        }
    }
}
