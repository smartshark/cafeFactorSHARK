#!/bin/sh
PLUGIN_PATH=$1
REPOSITORY_PATH=$2
NEW_UUID=$(cat /dev/urandom | tr -dc 'a-zA-Z0-9' | fold -w 32 | head -n 1)

cp -R $REPOSITORY_PATH "/dev/shm/$NEW_UUID"

COMMAND="java -jar $PLUGIN_PATH/build/cafeFactorSHARK.jar --input /dev/shm/$NEW_UUID --url $3 --db-hostname $4 --db-port $5 --db-database $6"

if [ ! -z ${7+x} ] && [ ${7} != "None" ]; then
	COMMAND="$COMMAND --db-user ${7}"
fi

if [ ! -z ${8+x} ] && [ ${8} != "None" ]; then
	COMMAND="$COMMAND --db-password ${8}"
fi

if [ ! -z ${9+x} ] && [ ${9} != "None" ]; then
	COMMAND="$COMMAND --db-authentication ${9}"
fi

if [ ! -z ${10+x} ] && [ ${10} != "None" ]; then
	COMMAND="$COMMAND -ssl"
fi

if [ ! -z ${11+x} ] && [ ${11} != "None" ]; then
    COMMAND="$COMMAND --debug ${11}"
fi


$COMMAND

rm -rf "/dev/shm/$NEW_UUID"
