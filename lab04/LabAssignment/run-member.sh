#!/bin/bash
source setenv.sh
java -Dhazelcast.socket.bind.any=false ClusterMember "$@"