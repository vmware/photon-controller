#!/bin/bash -xe

yum -y install --nogpgcheck http://ftp.riken.jp/Linux/fedora/epel/6/i386/epel-release-6-8.noarch.rpm && yum clean all
yum -y install --nogpgcheck golang && yum clean all

cd /esxcloud/java
if [ -d /esxcloud/java/distributions ] ; then
  rm -rf /esxcloud/java/distributions/*
else
  mkdir /esxcloud/java/distributions
fi
mkdir /esxcloud/java/distributions/configurations

echo Clean build folders
rm -rf api-frontend/management/build
rm -rf root-scheduler/build
rm -rf housekeeper/build
rm -rf cloud-store/build
rm -rf deployer/build
rm -rf auth-tool/build

echo Build all tarballs in parallel
/esxcloud/java/gradlew distTar --parallel -p /esxcloud/java

echo Consolidate tarballs and config files
mv /esxcloud/java/api-frontend/management/build/distributions/management-*.tar \
  /esxcloud/java/distributions/management-api.tar
cp -r /esxcloud/java/api-frontend/management/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-management-api

mv /esxcloud/java/deployer/build/distributions/deployer-*.tar \
  /esxcloud/java/distributions/deployer.tar
cp -r /esxcloud/java/deployer/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-deployer

mv /esxcloud/java/housekeeper/build/distributions/housekeeper-*.tar \
  /esxcloud/java/distributions/housekeeper.tar
cp -r /esxcloud/java/housekeeper/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-housekeeper

mv /esxcloud/java/cloud-store/build/distributions/cloud-store-*.tar \
  /esxcloud/java/distributions/cloud-store.tar
cp -r /esxcloud/java/cloud-store/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-cloud-store

mv /esxcloud/java/root-scheduler/build/distributions/root-scheduler-*.tar \
  /esxcloud/java/distributions/root-scheduler.tar
cp -r /esxcloud/java/root-scheduler/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-root-scheduler

mv /esxcloud/java/bare-metal-provisioner/build/distributions/*.tar \
  /esxcloud/java/distributions/bare-metal-provisioner.tar
cp -r /esxcloud/java/bare-metal-provisioner/src/dist/configuration \
  /esxcloud/java/distributions/configurations/configuration-bare-metal-provisioner

cp -r /esxcloud/java/deployer/src/dist/configuration-* /esxcloud/java/distributions/configurations/
