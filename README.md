# Two Phase commit

This project includes a `client` and `server` folder containing the client and server respectively.

## Running instructions

The project uses `maven` so to build it on your system you will need maven installed. For inter process communication `gRPC` is used.

This project has to be run with Docker and Docker compose which will recreate a coordinator and the specified number of server replicas.

### Run with Docker

```sh
# Run the docker compose command
# This will create the network, start the coordinator and the specified number of replicas
docker compose up

# Run: 'docker compose down' when you want to remove the resources created above 

# Run client container
docker run -it --rm --name client-con --network tpc_default client-img java -jar /app/client.jar tpc-server-3 5000

# If the above command doesnt work (it didnt work for me on windows git bash) try this one
# docker run -it --rm --name client-con --network project-net client-img java -jar //app//client.jar server-con 5000
```

Since I'm using a windows system I haven't tested the bash scripts, but I have modified them to reflect the steps above

```sh
./run_client.sh 5000
```

If you want to change the number of replicas edit `compose.yaml` and rerun the commands.

```Dockerfile
replicas : 5
```

To change the ports that the servers run on you can also edit

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
