import java.rmi.Remote;
import java.rmi.RemoteException;

public interface ReplicaInterface extends Remote {

    boolean prepare(String request) throws RemoteException;

    String commit(String request) throws RemoteException;

    boolean abort() throws RemoteException;

    boolean isAlive() throws RemoteException;

    String generateResponse(String requestStr) throws RemoteException;
}
