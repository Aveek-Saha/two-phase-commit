import java.rmi.Remote;
import java.rmi.RemoteException;

/**
 * an interface for the coordinator in the two phase commit protocol
 */
public interface CoordinatorInterface extends Remote {
    /**
     * Starts a transaction using the Two phase commit protocol when called by a replica
     *
     * @param request the request sent by the replica in string form
     * @return the result of the transaction as a string
     * @throws RemoteException If there is an error during the remote call
     */
    String startTransaction(String request) throws RemoteException;

    /**
     * Adds a replica to the list of available replicas and adds the replica name to the name list
     *
     * @param replica the replica to add to the replica list
     */
    void addReplica(ReplicaInterface replica) throws RemoteException;
}
