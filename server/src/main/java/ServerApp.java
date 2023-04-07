

/**
 * The main entrypoint for the server
 */
public class ServerApp {

    /**
     * The starting point for the server.
     *
     * @param args takes one argument, the registry port to start the server with
     */
    public static void main(String[] args) {

        if (args.length != 3 && args.length != 2) {
            ServerLogger.logError("Incorrect parameters provided, correct syntax is: " +
                    "java -jar <path to jar>/server.jar " +
                    "<coordinator hostname> <coordinator port> <port>\n OR \n" +
                    "java -jar <path to jar>/server.jar c <port>");
            System.exit(1);
        }

        if (args[0].equals("c")) {
            try {
                int port = Integer.parseInt(args[1]);

                Coordinator coordinator = new Coordinator();
                coordinator.start(port);
                coordinator.blockUntilShutdown();

            } catch (Exception e) {
                ServerLogger.logError("Coordinator exception: " + e);
            }
        } else {
            try {
                String coordinatorHostname = args[0];
                int coordinatorPort = Integer.parseInt(args[1]);
                int serverPort = Integer.parseInt(args[2]);
                //Registry registry = LocateRegistry.getRegistry(coordinatorHostname, coordinatorPort);
                //CoordinatorInterface coordinator = (CoordinatorInterface) registry.lookup("RemoteCoordinator");
                //
                //String serverName = "Server-" + serverPort;
                //Replica replica = new Replica(coordinator);
                //coordinator.addReplica(replica);
                //ServerLogger.log("Connected to coordinator");
                //
                //registry = LocateRegistry.createRegistry(serverPort);
                //registry.bind(serverName, replica);
                //
                //ServerLogger.log("Server ready");

                String coordinator = coordinatorHostname + ":" + coordinatorPort;
                Replica replica = new Replica();
                replica.start(coordinator, serverPort);
                replica.blockUntilShutdown();

            } catch (Exception e) {
                ServerLogger.logError("Server exception: " + e);
            }

        }
    }
}
