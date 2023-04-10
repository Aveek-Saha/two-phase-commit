import com.example.client.Request;
import com.example.client.Response;
import com.example.client.ServiceGrpc;
import com.example.client.ServiceGrpc.ServiceBlockingStub;

import java.util.Scanner;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;

/**
 * The main entrypoint for the client
 */
public class ClientApp {
    /**
     * The starting point for the client.
     *
     * @param args takes two arguments, the hostname/IP of the server, the port number to connect to
     *             the server on.
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
            String server = serverHost + ":" + serverPort;
            ManagedChannel serverChannel =
                    Grpc.newChannelBuilder(server, InsecureChannelCredentials.create()).build();
            ServiceBlockingStub serverStub = ServiceGrpc.newBlockingStub(serverChannel);

            ClientLogger.log("Starting client");
            System.out.println();

            ClientLogger.log("Pre-populating the KV store");
            prePopulateKVStore(serverStub);
            ClientLogger.log("Performing 5 of each type of operation on the KV store");
            performOperations(serverStub);

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
                    Request request = Client.formatInput(input);
                    if (request == null) {
                        continue;
                    }
                    ClientLogger.log("Request to server: " + request);

                    // Send the request to the server
                    Response response = serverStub.generateResponse(request);

                    // Format the response from the server
                    Client.formatResponse(response);

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
    private static void prePopulateKVStore(ServiceBlockingStub stub) {
        String[] commands = new String[]{"put hello world", "put create 123", "put dist systems",
                "put name aveek", "put age 25", "put score 100", "put home work", "put lang java"};

        for (String command : commands) {
            Response response = stub.generateResponse(Client.formatInput(command));
            Client.formatResponse(response);
        }
    }

    /**
     * Automatically performs 5 of each type of operation (GET, PUT and DEL)
     *
     * @param stub an instance of the remote interface
     */
    private static void performOperations(ServiceBlockingStub stub) {
        String[] putCommands =
                new String[]{"put university neu", "put semester spring", "put year 2024",
                        "put course computer science", "put grade A+"};

        for (String command : putCommands) {
            Response response = stub.generateResponse(Client.formatInput(command));
            Client.formatResponse(response);
        }

        String[] getCommands =
                new String[]{"get university", "get semester", "get year", "get course",
                        "get grade"};

        for (String command : getCommands) {
            Response response = stub.generateResponse(Client.formatInput(command));
            Client.formatResponse(response);
        }

        String[] delCommands =
                new String[]{"del university", "del semester", "del year", "del course",
                        "del grade"};

        for (String command : delCommands) {
            Response response = stub.generateResponse(Client.formatInput(command));
            Client.formatResponse(response);
        }
    }
}
