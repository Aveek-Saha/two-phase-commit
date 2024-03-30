import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.rmi.RemoteException;
import java.rmi.registry.LocateRegistry;
import java.rmi.registry.Registry;
import java.security.NoSuchAlgorithmException;
import java.util.Scanner;

/**
 * The main entrypoint for the client
 */
public class ClientApp {
    /**
     * The starting point for the client.
     *
     * @param args takes two arguments, the hostname/IP of the server, the port number to connect
     *             to the server on.
     */
    public static void main(String[] args) {

        if (args.length != 2) {
            ClientLogger.logError("Incorrect parameters provided, correct syntax is: " +
                    "java -jar <path to jar>/client.jar <hostname/IP> <port>");
            System.exit(1);
        }

        String serverHost = args[0];
        int serverPort = Integer.parseInt(args[1]);


        try {
            String serverName = "Server-" + serverPort;
            Registry registry = LocateRegistry.getRegistry(serverHost, serverPort);
            ReplicaInterface stub = (ReplicaInterface) registry.lookup(serverName);

            ClientLogger.log("Starting client");
            System.out.println();

            ClientLogger.log("Pre-populating the KV store");
//            prePopulateKVStore(stub);
            ClientLogger.log("Performing 5 of each type of operation on the KV store");
//            performOperations(stub);

            System.out.println();
            ClientLogger.log("Input format is: METHOD KEY [VALUE]");
            ClientLogger.log("Example: put key value");
            ClientLogger.log("Example: get key");
            ClientLogger.log("Example: del key");

            System.out.println();

            try (Scanner scanner = new Scanner(System.in)) {
                // Start an infinite loop to continuously wait for user input and send messages
                while (true) {
                    // Prompt user for method
                    System.out.print("Enter command : ");

                    String input = scanner.nextLine().trim();
                    String request = Client.formatInput(input);
                    if (request == null) {
                        continue;
                    }
                    ClientLogger.log("Request to server: " + request);

                    // Generate the checksum for the request
                    String checksum = null;
                    try {
                        checksum = Client.getChecksum(request);
                    } catch (NoSuchAlgorithmException | IOException e) {
                        ClientLogger.logError("Error generating checksum: " + e.getMessage());
                    }

                    // Send the request to the server
                    String resString = stub.generateResponse(request);

                    // Get the response from the server
                    if (resString != null) {
                        try {
                            JSONObject response = new JSONObject(resString);
                            String status = response.getString("status");
                            String message = response.getString("message");
                            String receivedChecksum = response.getString("checksum");

                            // Log an error if the checksum does not match
                            if (receivedChecksum.equals(checksum)) {
                                if (status.equalsIgnoreCase("400")) {
                                    ClientLogger.logError(message);
                                } else {
                                    ClientLogger.log(message);
                                }
                            } else {
                                ClientLogger.logError("Un-requested response received, checksums do not match");
                            }
                        } catch (JSONException e) {
                            ClientLogger.logError("Error parsing JSON: " + e.getMessage());
                        }
                    }

                }
            }
        } catch (Exception e) {
            ClientLogger.logError("Client exception: " + e.getMessage());
        }

    }

    /**
     * Automatically pre-populates the server with some data
     *
     * @param stub an instance of the remote interface
     */
    private static void prePopulateKVStore(ReplicaInterface stub) {
        String[] commands = new String[]{"put hello world", "put create 123", "put dist systems",
                "put name aveek", "put age 25", "put score 100", "put home work", "put lang java"};

        for (String command : commands) {
            try {
                stub.generateResponse(Client.formatInput(command));
            } catch (RemoteException e) {
                ClientLogger.logError("Client remote exception: " + e.getMessage());
            }
        }
    }

    /**
     * Automatically performs 5 of each type of operation (GET, PUT and DEL)
     *
     * @param stub an instance of the remote interface
     */
    private static void performOperations(ReplicaInterface stub) {
        String[] putCommands = new String[]{"put university neu", "put semester spring",
                "put year 2024", "put course computer science", "put grade A+"};

        for (String command : putCommands) {
            try {
                stub.generateResponse(Client.formatInput(command));
            } catch (RemoteException e) {
                ClientLogger.logError("Client remote exception: " + e.getMessage());
            }
        }

        String[] getCommands = new String[]{"get university", "get semester", "get year",
                "get course", "get grade"};

        for (String command : getCommands) {
            try {
                stub.generateResponse(Client.formatInput(command));
            } catch (RemoteException e) {
                ClientLogger.logError("Client remote exception: " + e.getMessage());
            }
        }

        String[] delCommands = new String[]{"del university", "del semester", "del year",
                "del course", "del grade"};

        for (String command : delCommands) {
            try {
                stub.generateResponse(Client.formatInput(command));
            } catch (RemoteException e) {
                ClientLogger.logError("Client remote exception: " + e.getMessage());
            }
        }
    }
}
