#!/usr/bin/env bash
#
# File:         bod-status.sh
# Description:  Prints key metrics of a bolt-of-death topology to decide whether it has entered a zombie state.
#               The metrics are retrieved from the Storm REST API.
#
# Requirements: jq 1.4+
#
# References:
# - https://github.com/apache/storm/blob/master/STORM-UI-REST-API.md
# - http://stedolan.github.io/jq/manual/ for how to use `jq`

###
### Configuration
###

# Storm environment configuration -- most likely you need to modify these to match your cluster
declare -r NIMBUS_HOST="nimbus1"
declare -r UI_PORT="8080"
declare -r API="http://${NIMBUS_HOST}:${UI_PORT}/api/v1"

# Configuration of bolt-of-death topology -- only modify if you are not using the default settings of bolt-of-death
declare -r TOPOLOGY_NAME="bolt-of-death-topology"
declare -r SPOUT="wordSpout"
declare -r BOLT_A1="forwarder-A1"
declare -r BOLT_A2="bolt-of-death-A2"
declare -r BOLT_B1="noop-B1"
declare -r ACKER_BOLT="__acker" # system bolt used by Storm
declare -a COMPONENTS=($SPOUT $BOLT_A1 $BOLT_A2 $BOLT_B1 $ACKER_BOLT)

# Convenience variables for console output
declare -r SEP="================================================================================"
declare -r sep="--------------------------------------------------------------------------------"


###
### Start of main application
###

BOD_TOPOLOGY=`curl -s $API/topology/summary | jq -r ".topologies |.[] |.encodedId | select(. | contains(\"$TOPOLOGY_NAME\") )"`


###
### Report metrics to console
###

# Header
echo $SEP
echo "`basename $0` -- displays key metrics of $TOPOLOGY_NAME"

# Print topology stats
echo $SEP
echo "Topology: $BOD_TOPOLOGY"
echo $sep
curl -s $API/topology/summary | jq '.topologies |.[] | select(.id | contains("bolt-of-death-topology") )'

# Print topology map
echo $sep
echo
echo "                +-----> $BOLT_A1 -----> $BOLT_A2"
echo "                |"
echo " $SPOUT -----+"
echo "                |"
echo "                +-----> $BOLT_B1"
echo
echo $SEP

# Print key metrics of components (spouts and bolts)
for comp in "${COMPONENTS[@]}"; do
  echo
  echo $SEP
  echo "Component: $comp"
  echo $SEP
  echo "Metrics of $comp"
  echo $sep
  curl -s $API/topology/$BOD_TOPOLOGY/component/$comp | jq -c '.executorStats|.[]|{ uptime: (if ((.uptime | length) > 0) then .uptime else "n/a" end), worker: (.host + ":" + (.port | tostring)), emitted: .emitted, transferred: .transferred, acked: .acked, failed: .failed }'
  LATEST_ERRORS_CMD="curl -s $API/topology/$BOD_TOPOLOGY/component/$comp | jq -c '.componentErrors|.[]|{errorLapsedSecs: .errorLapsedSecs, worker: (.errorHost + \":\" + (.errorPort | tostring)), error: .error[:150] }'"
  LATEST_ERRORS=`eval $LATEST_ERRORS_CMD`
  if  [ -n "$LATEST_ERRORS" ]; then
    echo $sep
    echo "Latest errors of $comp"
    echo $sep
    eval $LATEST_ERRORS_CMD
  fi
  echo $SEP
done

# Footer
echo
echo "TIP: You should also inspect the associated worker logs."
