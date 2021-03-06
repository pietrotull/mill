#!/usr/bin/env bash

set -eux

# Starting from scratch...
git clean -xdf

# First build using SBT
sbt bin/test:assembly

# Build Mill using SBT
target/bin/mill --all \
    moduledefs.publishLocal \
    core.publishLocal \
    scalalib.publishLocal \
    scalaworker.publishLocal \
    scalajslib.publishLocal \
    scalajslib.jsbridges[0.6].publishLocal \
    scalajslib.jsbridges[1.0].publishLocal \
    releaseAssembly

mv out/releaseAssembly/dest/out.jar ~/mill-release

git clean -xdf

# Second build & run tests using Mill

~/mill-release --all {core,scalalib,scalajslib}.test devAssembly
~/mill-release integration.test mill.integration.AmmoniteTests
~/mill-release integration.test "mill.integration.{AcyclicTests,BetterFilesTests,JawnTests}"
~/mill-release devAssembly
