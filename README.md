# Two Phase commit

This project is an implementation of the two phase commit protocol in a distributed key value store. The project uses `maven` as a build tool and `gRPC` for communication.

The server can be replicated to any number of instances and the data in the Key Value store will remain consistent across replicas.

The server has specifications as follows:
- Three types of operations should be performed on the server with the following parameters:
    - PUT (key, value) 
    - GET (key) 
    - DELETE (key) 
- Server is multi threaded and can respond to multiple clients at a time. 
- The server is replicated across multiple instances
- The two phase commit protocol is used to maintain consistency across these replicas
- The Coordinator handles timeouts in the case of unresponsive servers


## Running instructions

This repo includes a `client` and `server` project containing the client and server respectively.

This project has to be run with Docker and Docker compose which will recreate a coordinator and the specified number of server replicas.

### Run with Docker Compose

```sh
# Run the docker compose command
# This will create the network, start the coordinator and the specified number of replicas
docker compose up

# Run: 'docker compose down' when you want to remove the resources created above 

# Build client image
docker build -f client.Dockerfile -t client-img --target client-build .

# Run client container
docker run -it --rm --name client-con --network project3_default client-img java -jar /app/client.jar project3-server-3 5001

# If the above command doesnt work (it didn't work for me on windows git bash) try this one
# docker run -it --rm --name client-con --network project-net client-img java -jar //app//client.jar server-con 5001
```

Since I'm using a windows system I haven't tested the bash scripts, but I have modified them to reflect the steps above

```sh
./run_client.sh 5001
```

If you want to change the number of replicas edit `compose.yaml` and rerun the commands.

```Dockerfile
replicas : 10
```

To change the ports that the servers run on you can also edit `compose.yaml`

```Dockerfile
coordinator:
    ...
    entrypoint: java -jar /app/server.jar c <coordinator port>
    ports:
        - "<coordinator port>:<coordinator port>"
    ...

server:
    ...
    entrypoint: java -jar /app/server.jar coordinator <coordinator port> <server port>
    ports:
        - target: <server port>
    ...
```

### Run with Docker
If for some reason you are unable to get Docker compose working, Docker can be used along with the shell scripts. However it is more inconvenient to set up and manage so Docker compose is still the recommended way.

```sh
# This script will build and start the coordinators and servers and then display logs from the coordinator
./deploy.sh

# To view logs from any of the 5 servers
docker logs project3-server-<1-5> -f

# Run the client
./run_client.sh project3-server-<1-5> <5001-5005>
```
