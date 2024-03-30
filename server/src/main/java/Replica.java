import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.rmi.RemoteException;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.rmi.server.UnicastRemoteObject;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

public class Replica extends UnicastRemoteObject implements ReplicaInterface {

    private final Lock lock;
    KeyValue kvs;
    CoordinatorInterface coordinator;

    /**
     * Initialises the server with a key value store and a lock
     */
    public Replica(CoordinatorInterface coordinator) throws RemoteException {
        super();
        kvs = new KeyValue();
        lock = new ReentrantLock();
        this.coordinator = coordinator;
    }


    /**
     * Generate a checksum for the received request using the SHA-256 algorithm
     *
     * @param object the JSON object to generate the checksum for
     * @return A string hexadecimal checksum for the request
     * @throws IOException              If an error occurs while writing the Obj output stream
     * @throws NoSuchAlgorithmException Thrown if a crypto algorithm is requested but available
     */
    public static String getChecksum(Object object) throws IOException, NoSuchAlgorithmException {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digestedArr = sha.digest(baos.toByteArray());
            return new BigInteger(1, digestedArr).toString(16);

        }
    }

    /**
     * A function that accepts response values and puts them in a JSON object
     *
     * @param status  the status to be sent in the response
     * @param message the message to be sent in the response
     * @param data    the data to be sent in the response
     * @return a JSON obj containing the response
     */
    public JSONObject jsonResponse(String status, String message, String data) {

        JSONObject response = new JSONObject();
        response.put("status", status);
        response.put("message", message);
        response.put("data", data);

        return response;
    }

    /**
     * Called by the coordinator when the server needs to prepare for a commit
     *
     * @param request the request to commit
     * @return true if the prepare phase was successful and false if not
     * @throws RemoteException If there is an error during the remote call
     */
    @Override
    public boolean prepare(String request) throws RemoteException {
        if (lock.tryLock()) {
            ServerLogger.logInfo("Acquired lock for prepare");
            return true;
        } else {
            ServerLogger.logWarning("Could not acquire lock for prepare");
            return false;
        }
    }

    /**
     * Called by the coordinator when the server needs to commit a transaction
     *
     * @param requestStr the request to commit
     * @return the response message after the commit was completed
     * @throws RemoteException If there is an error during the remote call
     */
    @Override
    public String commit(String requestStr) throws RemoteException {
        try {
            ServerLogger.logInfo("Received request from coordinator to commit: " + requestStr);

            JSONObject request;
            JSONObject response;
            try {
                request = new JSONObject(requestStr);
            } catch (JSONException e) {
                ServerLogger.logError("Error parsing JSON: " + e.getMessage());
                response = jsonResponse("400", "Invalid request format", null);
                return response.toString();
            }

            String method = request.getString("method");
            // Twp phase commit only required for put and delete since they modify the KV store
            switch (method.toUpperCase()) {
                case "PUT":
                    JSONObject data = request.getJSONObject("data");
                    response = handlePut(data);
                    break;
                case "DEL":
                    String delKey = request.getString("data");
                    response = handleDelete(delKey);
                    break;
                default:
                    response = jsonResponse("400", "Invalid commit request", null);
                    break;
            }
            ServerLogger.logInfo("Sent response to coordinator for commit: " + requestStr);
            return response.toString();
        } finally {
            try {
                lock.unlock();
                ServerLogger.logInfo("Unlocked after commit");
            } catch (IllegalMonitorStateException e) {
                ServerLogger.logWarning("Could not unlock after commit: " + e.getMessage());
            }
        }
    }

    /**
     * Called by the coordinator when a transaction has to be aborted
     *
     * @return true if the abort was successful and false if not
     * @throws RemoteException If there is an error during the remote call
     */
    @Override
    public boolean abort() throws RemoteException {
        try {
            lock.unlock();
            ServerLogger.logInfo("Unlocked during abort");
        } catch (IllegalMonitorStateException e) {
            ServerLogger.logWarning("Could not unlock during abort: " + e.getMessage());
            return false;
        }
        return true;
    }

    /**
     * Called by the coordinator to check if the server is alive
     *
     * @return true if the server is alive
     * @throws RemoteException If there is an error during the remote call
     */
    @Override
    public boolean isAlive() throws RemoteException {
        return true;
    }

    /**
     * Takes in an input request from the client and returns the appropriate response
     *
     * @param requestStr The formatted request as a string
     * @return The response to be sent to the client
     * @throws RemoteException If there is an error during the remote call
     */
    @Override
    public String generateResponse(String requestStr) throws RemoteException {


        String clientName = "unknown";
        try {
            clientName = RemoteServer.getClientHost();
        } catch (ServerNotActiveException e) {
            ServerLogger.logError("Error getting client info: " + e.getMessage());
        }

        ServerLogger.log("Received request from " + clientName + ": " + requestStr);

        // Generate a checksum to ensure that the client receives the response for the correct req
        String checksum = null;
        try {
            checksum = getChecksum(requestStr);
        } catch (NoSuchAlgorithmException | IOException e) {
            ServerLogger.logError("Error generating checksum: " + e.getMessage());
        }

        // Parse request JSON string and create empty JSON response
        JSONObject request;
        JSONObject response;
        try {
            request = new JSONObject(requestStr);
        } catch (JSONException e) {
            ServerLogger.logError("Error parsing JSON: " + e.getMessage());
            response = jsonResponse("400", "Invalid request format", null);
            response.put("checksum", checksum);
            return response.toString();
        }

        // Process request
        String method = request.getString("method");

        switch (method.toUpperCase()) {
            case "GET":
                String getKey = request.getString("data");
                response = handleGet(getKey);
                break;
            case "PUT":
            case "DEL":
                String resString = coordinator.startTransaction(requestStr);
                response = new JSONObject(resString);
                break;
            default:
                response = jsonResponse("400",
                        "Invalid method. Valid methods are " + "GET, PUT and DEL", null);
                break;
        }

        // Add the checksum to the response JSON
        response.put("checksum", checksum);
        ServerLogger.log("Sent response to " + clientName + ": " + response);

        return response.toString();
    }

    /**
     * Handles getting the corresponding value for a key from the KV store if it exists.
     *
     * @param key the key to be inserted
     * @return the message to return to the client along with any data as a JSON string
     */
    public JSONObject handleGet(String key) {
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
        return jsonResponse(status, message, value);
    }

    /**
     * Handles putting key value pairs into the KV store
     *
     * @param data the key value pair to be inserted, in JSON format
     * @return the message to return to the client along with any data as a JSON string
     */
    public JSONObject handlePut(JSONObject data) {
        lock.lock();
        try {
            String key = data.keys().next();
            String value = data.getString(key);
            String message;
            String status;

            // Return a success if the key was successfully put into the KV store
            if (kvs.put(key, value)) {
                ServerLogger.log("Successful PUT on key '" + key + "' with value '" + value + "'");
                message = "Put key '" + key + "' with value '" + value + "'";
                status = "200";
            } else {
                ServerLogger.logError("Could not PUT key '" + key + "'");
                message = "PUT FAILED for key '" + key + "' with value '" + value + "'";
                status = "400";
            }
            return jsonResponse(status, message, null);
        } finally {
            lock.unlock();
        }
    }

    /**
     * Handles deleting a key value pair from the KV store
     *
     * @param key the key to delete from the KV store
     * @return the message to return to the client along with any data as a JSON string
     */
    public JSONObject handleDelete(String key) {
        lock.lock();
        try {
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
            return jsonResponse(status, message, null);
        } finally {
            lock.unlock();
        }
    }
}
