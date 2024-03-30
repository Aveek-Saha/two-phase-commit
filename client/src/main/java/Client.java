import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * A class with some helper methods for processing user inputs and requests
 */
class Client {
    /**
     * Converts input from the user into a JSON format
     *
     * @param input the user input from the terminal
     * @return a JSON object format of the input data
     */
    public static String formatInput(String input) {

        String[] inputs = input.split(" ", 3);

        // Checks if the input is in the correct format
        if (inputs.length != 2 && inputs.length != 3) {
            ClientLogger.logError("Incorrect command syntax");
            return null;
        }

        String method = inputs[0];

        // Validate method
        if (!method.equalsIgnoreCase("GET")
                && !method.equalsIgnoreCase("PUT")
                && !method.equalsIgnoreCase("DEL")) {
            ClientLogger.logError("Invalid method. Valid methods are GET, PUT, or DELETE.");
            return null;
        }

        // Prepare request based on method
        JSONObject request = new JSONObject();
        if (method.equalsIgnoreCase("GET")
                || method.equalsIgnoreCase("DEL")) {
            if (inputs.length != 2) {
                ClientLogger.logError("Incorrect syntax for " + method.toUpperCase());
                return null;
            }
            String key = inputs[1];
            request.put("method", method);
            request.put("data", key);

        } else {
            if (inputs.length != 3) {
                ClientLogger.logError("Incorrect syntax for " + method.toUpperCase());
                return null;
            }
            String key = inputs[1];
            String value = inputs[2];

            request.put("method", method);
            request.put("data", new JSONObject().put(key, value));
        }

        return request.toString();
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
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream(); ObjectOutputStream oos = new ObjectOutputStream(baos)) {
            oos.writeObject(object);

            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            byte[] digestedArr = sha.digest(baos.toByteArray());
            return new BigInteger(1, digestedArr).toString(16);

        }
    }
}
