![Maven Build](https://github.com/samagra-comms/outbound/actions/workflows/build.yml/badge.svg)
![Docker Build](https://github.com/samagra-comms/outbound/actions/workflows/docker-build-push.yml/badge.svg)

# Overview
Outbound converts the xMessage to the one that will be sent to the channel(sms/whatsapp). It will then be sent to the network provider(Netcore/Gupshup) who will send it to the channel. 

# Getting Started

## Prerequisites

* java 11 or above
* docker
* kafka
* postgresql
* redis
* fusion auth
* lombok plugin for IDE
* maven

## Build
* build with tests run using command **mvn clean install -U**
* or build without tests run using command **mvn clean install -DskipTests**

# Detailed Documentation
[Click here](https://uci.sunbird.org/use/developer/uci-basics)