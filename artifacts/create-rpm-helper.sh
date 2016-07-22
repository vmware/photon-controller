#!/bin/bash
set -x -e

VERSION=$1
COMMIT=$2
DEBUG=$3

USER=`stat -c '%u' .`
GROUP=`stat -c '%g' .`

cd rpms
chown root:root ./SPECS/*
chown root:root /usr/src/photon/SOURCES/*

if [ "$DEBUG" == "true" ]; then
  bash
else
  rpmbuild -ba --define="pkg_version $VERSION" --define="pkg_commit $COMMIT" ./SPECS/photon-controller.spec
  createrepo --database /usr/src/photon/RPMS
  tar -czf ../build/photon-controller-rpmrepo-"$VERSION".tar.gz -C /usr/src/photon/ RPMS
fi

chown -R ${USER}:${GROUP} ./SPECS/*
chown -R ${USER}:${GROUP} /usr/src/photon
