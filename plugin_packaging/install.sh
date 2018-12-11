#!/bin/sh
PLUGIN_PATH=$1

cd $PLUGIN_PATH/cafeFactorSHARK

# Build jar file or perform other tasks
./gradlew shadowJar

cp build/libs/cafeFactorSHARK*.jar ../build/cafeFactorSHARK.jar