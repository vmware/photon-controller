#!/bin/bash -xe

VM_DRIVER=vmwarefusion

# If virtualbox is used VM_DRIVER then change following to eth1
IF=eth0

docker-machine create -d $VM_DRIVER mh-keystore

eval "$(docker-machine env mh-keystore)"

docker run -d \
    -p "8500:8500" \
    -h "consul" \
    progrium/consul -server -bootstrap

docker-machine create \
    -d $VM_DRIVER \
    --swarm --swarm-master \
    --swarm-discovery="consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-store=consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-advertise=$IF:2376" \
    mhs-demo0

docker-machine create \
    -d $VM_DRIVER \
    --swarm \
    --swarm-discovery="consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-store=consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-advertise=$IF:2376" \
  mhs-demo1

docker-machine create \
    -d $VM_DRIVER \
    --swarm \
    --swarm-discovery="consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-store=consul://$(docker-machine ip mh-keystore):8500" \
    --engine-opt="cluster-advertise=$IF:2376" \
  mhs-demo2

eval $(docker-machine env --swarm mhs-demo0)

docker network create --driver overlay --subnet=192.168.114.0/27 lightwave

find . -iname photon-controller-docker-*.tar | xargs docker load -i
docker load < ./vmware-lightwave-sts.tar
docker tag vmware/photon-controller vmware/photon-controller-lightwave-client
