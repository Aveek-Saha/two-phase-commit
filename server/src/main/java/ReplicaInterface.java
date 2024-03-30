import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 *
 */
public interface ReplicaInterface extends Remote {

    /**
     * Called by the coordinator when the server needs to prepare for a commit
     *
     * @param request the request to commit
     * @return true if the prepare phase was successful and false if not
     * @throws RemoteException If there is an error during the remote call
     */
    boolean prepare(String request) throws RemoteException;

    /**
     * Called by the coordinator when the server needs to commit a transaction
     *
     * @param request the request to commit
     * @return the response message after the commit was completed
     * @throws RemoteException If there is an error during the remote call
     */
    String commit(String request) throws RemoteException;

    /**
     * Called by the coordinator when a transaction has to be aborted
     *
     * @return true if the abort was successful and false if not
     * @throws RemoteException If there is an error during the remote call
     */
    boolean abort() throws RemoteException;

    /**
     * Called by the coordinator to check if the server is alive
     *
     * @return true if the server is alive
     * @throws RemoteException If there is an error during the remote call
     */
    boolean isAlive() throws RemoteException;

    /**
     * Takes in an input request from the client and returns the appropriate response
     *
     * @param requestStr The formatted request as a string
     * @return The response to be sent to the client
     * @throws RemoteException If there is an error during the remote call
     */
    String generateResponse(String requestStr) throws RemoteException;
}
