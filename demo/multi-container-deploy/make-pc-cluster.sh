#!/bin/sh -xe

LIGHTWAVE_PARTNER_0=${LIGHTWAVE_PARTNER_0:-192.168.114.2}

PHOTON_PEER_0=${PHOTON_PEER_0:-192.168.114.11}
PHOTON_PEER_1=${PHOTON_PEER_1:-192.168.114.12}
PHOTON_PEER_2=${PHOTON_PEER_2:-192.168.114.13}

./run-pc-container.sh $PHOTON_PEER_0 $PHOTON_PEER_1 $PHOTON_PEER_2 $LIGHTWAVE_PARTNER_0 0
./run-pc-container.sh $PHOTON_PEER_1 $PHOTON_PEER_0 $PHOTON_PEER_2 $LIGHTWAVE_PARTNER_0 1
./run-pc-container.sh $PHOTON_PEER_2 $PHOTON_PEER_0 $PHOTON_PEER_1 $LIGHTWAVE_PARTNER_0 2
