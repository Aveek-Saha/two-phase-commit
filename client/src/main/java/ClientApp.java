import com.example.server.Request;
import com.example.server.Response;
import com.example.server.ServiceGrpc;
import com.example.server.ServiceGrpc.ServiceBlockingStub;

import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import io.grpc.Grpc;
import io.grpc.InsecureChannelCredentials;
import io.grpc.ManagedChannel;
import io.grpc.StatusRuntimeException;

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

            ConcurrentHashMap<Integer, String> data;
            int NUM_REQUESTS = 100;
            int VALUE_LENGTH = 16;

            data = generateData(NUM_REQUESTS, VALUE_LENGTH);

            ClientLogger.log("Warm up client");
            System.out.println();

            benchmark(serverStub, "put", data, NUM_REQUESTS);
            benchmark(serverStub, "del", data, NUM_REQUESTS);
            benchmark(serverStub, "put", data, NUM_REQUESTS);
            benchmark(serverStub, "del", data, NUM_REQUESTS);
            benchmark(serverStub, "put", data, NUM_REQUESTS);
            benchmark(serverStub, "del", data, NUM_REQUESTS);

            data = generateData(NUM_REQUESTS, VALUE_LENGTH);

            long durationNano;

            ClientLogger.log("Benchmark inserting into the KV store");
            durationNano = benchmark(serverStub, "put", data, NUM_REQUESTS);
            System.out.println(
                    "Benchmark completed in " + TimeUnit.NANOSECONDS.toMillis(durationNano) +
                            " ms");
            System.out.println();

            ClientLogger.log("Benchmark fetching the KV store");
            durationNano = benchmark(serverStub, "get", data, NUM_REQUESTS);
            System.out.println(
                    "Benchmark completed in " + TimeUnit.NANOSECONDS.toMillis(durationNano) +
                            " ms");
            System.out.println();

            ClientLogger.log("Benchmark deleting from the KV store");
            durationNano = benchmark(serverStub, "del", data, NUM_REQUESTS);
            System.out.println(
                    "Benchmark completed in " + TimeUnit.NANOSECONDS.toMillis(durationNano) +
                            " ms");
            System.out.println();

            //ClientLogger.log("Input format is: METHOD KEY [VALUE]");
            //ClientLogger.log("Example: put key value");
            //ClientLogger.log("Example: get key");
            //ClientLogger.log("Example: del key");
            //
            //System.out.println();

            //try (Scanner scanner = new Scanner(System.in)) {
            //    // Start an infinite loop to continuously wait for user input and send messages
            //    while (true) {
            //        // Prompt user for method
            //        System.out.print("Enter command : ");
            //
            //        String input = scanner.nextLine().trim();
            //        Request request = Client.formatInput(input);
            //        if (request == null) {
            //            continue;
            //        }
            //        ClientLogger.log("Request to server: " + request);
            //
            //        // Send the request to the server
            //        Response response = serverStub.generateResponse(request);
            //
            //        // Format the response from the server
            //        Client.formatResponse(response);
            //
            //    }
            //}
        } catch (Exception e) {
            ClientLogger.logError("Client exception: " + e.getMessage());
        }

    }

    private static ConcurrentHashMap<Integer, String> generateData(int NUM_REQUESTS,
                                                                   int VALUE_LENGTH) {
        Random random = new Random();
        ConcurrentHashMap<Integer, String> data = new ConcurrentHashMap<>();
        for (int i = 0; i < NUM_REQUESTS; i++) {
            String value = generateRandomString(random, VALUE_LENGTH);
            data.put(i, value);
        }

        return data;
    }

    private static long benchmark(ServiceBlockingStub stub, String method,
                                  ConcurrentHashMap<Integer, String> data, int NUM_REQUESTS) {
        Request[] commands = new Request[NUM_REQUESTS];
        ExecutorService executorService = Executors.newFixedThreadPool(NUM_REQUESTS);
        CountDownLatch latch = new CountDownLatch(NUM_REQUESTS);

        for (int key : data.keySet()) {
            switch (method) {
                case "get":
                    commands[key] = Client.formatInput("get " + key);
                    break;
                case "put":
                    commands[key] = Client.formatInput("put " + key + " " + data.get(key));
                    break;
                case "del":
                    commands[key] = Client.formatInput("del " + key);
                    break;
            }
        }

        long startTime = System.nanoTime();

        for (Request command : commands) {
            executorService.submit(() -> {
                try {
                    Response response = stub.generateResponse(command);
                    //Client.formatResponse(response);
                } catch (StatusRuntimeException e) {
                    System.err.println("Error sending request: " + e.getStatus());
                } finally {
                    latch.countDown();
                }
            });
        }

        try {
            latch.await();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }

        long endTime = System.nanoTime();
        long durationNano = endTime - startTime;

        executorService.shutdown();
        try {
            if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            executorService.shutdownNow();
            Thread.currentThread().interrupt();
        }

        return durationNano;
    }

    private static String generateRandomString(Random random, int length) {
        StringBuilder sb = new StringBuilder(length);
        for (int i = 0; i < length; i++) {
            sb.append((char) (random.nextInt(26) + 'a'));
        }
        return sb.toString();
    }

}
