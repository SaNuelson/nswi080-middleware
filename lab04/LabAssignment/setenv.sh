#!/bin/bash
# Set to your Hazelcast installation directory
export HAZELCAST_HOME=~/hazelcast/hazelcast-4.2.5
# Due to nested structure, path to project base needs to be added as well
export CLASSPATH=.:$HAZELCAST_HOME/lib/hazelcast-4.2.5.jar:src/main/java/
