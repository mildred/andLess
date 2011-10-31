#!/bin/sh

cd "`dirname "$0"`"

sed -r "s:\\\$\{project_loc\}:`pwd`:g" .externalToolBuilders/Version\ info.launch.template >.externalToolBuilders/Version\ info.launch
