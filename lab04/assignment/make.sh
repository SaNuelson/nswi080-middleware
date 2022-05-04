#!/bin/bash
source setenv.sh
javac `find src/* | grep "\.java"`
