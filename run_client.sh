# PROJECT_NETWORK='project-net'
# SERVER_CONTAINER='server-con'

PROJECT_NETWORK='project3_default'
SERVER_CONTAINER='project3-server3-1'
CLIENT_IMAGE='client-img'
CLIENT_CONTAINER='client-con'

if [ $# -ne 1 ]
then
  echo "Usage: ./run_client.sh <port-number>"
  exit
fi

# run client docker container with cmd args
docker run -it --rm --name $CLIENT_CONTAINER \
 --network $PROJECT_NETWORK $CLIENT_IMAGE \
 java -jar //app//client.jar $SERVER_CONTAINER "$1"

# Manual command 
# docker run -it --rm --name client-con-1 --network project-net client-img java -jar //app//client.jar server-con 5000
# docker run -it --rm --name client-con --network project3_default client-img java -jar //app//client.jar project3-server-3 32776