import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.rmi.server.RemoteServer;
import java.rmi.server.ServerNotActiveException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * A class that represents the functionality of the server and implements the remote methods.
 */
public class Server implements ServerInterface {

    private final Lock lock;
    KeyValue kvs;

    /**
     * Initialises the server with a key value store and a lock
     */
    public Server() {
        kvs = new KeyValue();
        lock = new ReentrantLock();
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
     * Parses the request sent by the client and sends the appropriate response
     *
     * @param requestStr the string form of the request sent by the client
     * @return the string form of the response to be sent to the client
     */
    @Override
    public String generateResponse(String requestStr) {

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
                JSONObject data = request.getJSONObject("data");
                response = handlePut(data);
                break;
            case "DEL":
                String delKey = request.getString("data");
                response = handleDelete(delKey);
                break;
            default:
                response = jsonResponse("400", "Invalid method. Valid methods are " +
                        "GET, PUT and DEL", null);
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
