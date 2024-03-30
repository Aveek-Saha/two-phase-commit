# Project 2

There are two folders `client` and `server` containing the client and server respectively. These are two separate Java programs and need to be compiled separately

## Running instructions

The project uses `maven` so to build it on your system you will need maven installed. If you use IntelliJ it should be already installed and you can install the package from the GUI.

The project has one dependency on an external library: `org.JSON`. This jar package needs to be installed using Maven before the code will compile.

Since this Project uses RMI your machine must be able set up for that as well.

This project has to be run with Docker and Docker compose which will recreate a coordinator and the specified number of server replicas.

### Run with Docker

```sh
# Run the docker compose command
# This will create the network, start the coordinator and the specified number of replicas
docker compose up

# Run: 'docker compose down' when you want to remove the resources created above 

# Run client container
docker run -it --rm --name client-con --network project3_default client-img java -jar /app/client.jar project3-server-3 5000

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


## Screenshots

### Server

![Server](./img/Proj2_server.png)

### Clients

**Client 1:**

![Client 1](./img/Proj2_client_1.png)

**Client 2:**

![Client 2](./img/Proj2_client_2.png)
