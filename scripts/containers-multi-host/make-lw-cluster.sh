#!/bin/sh -xe

LIGHTWAVE_PASSWORD="Admin!23"
LIGHTWAVE_MASTER_IP=192.168.114.2
LIGHTWAVE_PARTNER_1=192.168.114.3
LIGHTWAVE_PARTNER_2=192.168.114.4

function wait_to_start()
{
  echo "in function"
  echo $1
  node=$1
  # Check if lightwave server is up
  attempts=1
  reachable="false"
  total_attempts=50
  while [ $attempts -lt $total_attempts ] && [ $reachable != "true" ]; do
    http_code=$(docker-machine ssh $node curl -I -so /dev/null -w "%{response_code}" -s -X GET --insecure https://127.0.0.1) || true
    # The curl returns 000 when it fails to connect to the lightwave server
    if [ "$http_code" == "000" ]; then
      echo "Lightwave REST server $node not reachable (attempt $attempts/$total_attempts), will try again."
      attempts=$[$attempts+1]
      sleep 5
    else
      reachable="true"
      break
    fi
  done
  if [ $attempts -eq $total_attempts ]; then
    echo "Could not connect to Lightwave REST client at $node after $total_attempts attempts"
    exit 1
  fi
}

# Copy script to run/start containers in each VM
docker-machine ls -q | xargs -I {} docker-machine scp ./run-lw-container.sh {}:/tmp/

# Run first container
docker-machine ssh mhs-demo0 /tmp/run-lw-container.sh $LIGHTWAVE_MASTER_IP $LIGHTWAVE_MASTER_IP $LIGHTWAVE_PASSWORD standalone mhs-demo0

# Wait for the Lightwave to come up properly
wait_to_start mhs-demo0

docker-machine ssh mhs-demo1 /tmp/run-lw-container.sh $LIGHTWAVE_PARTNER_1 $LIGHTWAVE_MASTER_IP $LIGHTWAVE_PASSWORD partner mhs-demo1
wait_to_start mhs-demo1

docker-machine ssh mhs-demo2 /tmp/run-lw-container.sh $LIGHTWAVE_PARTNER_2 $LIGHTWAVE_MASTER_IP $LIGHTWAVE_PASSWORD partner mhs-demo2
wait_to_start mhs-demo2
