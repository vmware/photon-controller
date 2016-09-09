#!/bin/sh -xe

LIGHTWAVE_IP=$1
LIGHTWAVE_MASTER_IP=$2
LIGHTWAVE_PASSWORD=$3
LIGHTWAVE_DEPLOYMENT=$4
SWARM_NODE=$5

LIGHTWAVE_DOMAIN=photon.local
LIGHTWAVE_SITE=Default-first-site

LIGHTWAVE_CONFIG_DIR=/var/lib/vmware/config
LIGHTWAVE_CONFIG_PATH=$LIGHTWAVE_CONFIG_DIR/lightwave-server.cfg

mkdir -p $LIGHTWAVE_CONFIG_DIR

if [ ! -d /sys/fs/cgroup/systemd ]; then
  # boot2docker VMs do not have this directory present. Create it for systemd in lightwave container.
  sudo mkdir -p /sys/fs/cgroup/systemd
  sudo mount -t cgroup -o none,name=systemd cgroup /sys/fs/cgroup/systemd
  sudo mkdir -p /sys/fs/cgroup/systemd/user
  echo $$ | sudo tee -a /sys/fs/cgroup/systemd/user/cgroup.procs
fi

cat << EOF > $LIGHTWAVE_CONFIG_PATH
deployment=$LIGHTWAVE_DEPLOYMENT
domain=$LIGHTWAVE_DOMAIN
admin=Administrator
password=$LIGHTWAVE_PASSWORD
site-name=$LIGHTWAVE_SITE
hostname=$LIGHTWAVE_IP
first-instance=false
replication-partner-hostname=$LIGHTWAVE_MASTER_IP
disable-dns=1
EOF

docker run -d \
           --name lightwave-${SWARM_NODE} \
           --privileged \
           --net=lightwave \
           --ip=$LIGHTWAVE_IP \
           -p 443:443 \
           -e constraint:node==${SWARM_NODE} \
           -v /sys/fs/cgroup:/sys/fs/cgroup:ro \
           -v /var/lib/vmware/config:/var/lib/vmware/config \
           vmware/lightwave-sts
