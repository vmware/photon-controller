#!/bin/bash

LIGHTWAVE_LOAD_BALANCER_IP=$(docker inspect --format '{{ .Node.IP }}' haproxy) || true
UI_HOST_IP=192.168.114.15

if [ "${LIGHTWAVE_LOAD_BALANCER_IP}TEST" ==  "TEST" ]; then
  LIGHTWAVE_LOAD_BALANCER_IP=$(docker-machine ip default)
fi

./run-ui-container.sh $UI_HOST_IP $LIGHTWAVE_LOAD_BALANCER_IP 28080 4343
