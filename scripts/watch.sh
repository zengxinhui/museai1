#!/bin/bash
# Start shadow-cljs in watch mode: auto-compile on code changes and
# serve the app with hot reloading on http://localhost:3000/
# (see :dev-http in shadow-cljs.edn).
#
# NOTE: plain `npx shadow-cljs watch app` does NOT work in this VM:
# shadow-cljs resolves its Maven dependencies through an old wagon
# that cannot use the egress proxy (it tries a direct TLS connection
# to repo1.maven.org and fails). The dependencies themselves are all
# cached in ~/.m2/repository (populated by scripts/resolve_maven.py,
# which downloads via Python urllib through the proxy), so this
# script runs the compiler directly with a hand-built classpath
# instead. It is the exact equivalent of `npx shadow-cljs watch app`
# once the classpath is supplied. Same limitation as the release
# build documented in README.md.
#
# Hot reloading works through the :after-load hook in
# shadow-cljs.edn (nihongo.core/refresh): after a recompile the app is
# re-rendered into the existing root, so edits show up without a
# manual page refresh. Account data and UI state live in defonce
# atoms and survive the reload. Editing public/index.html (or other
# static files under public/) reloads the whole page instead, via
# :devtools :watch-dir.
#
# Usage: scripts/watch.sh            (watch + dev server on :3000)
#        scripts/watch.sh release app (one-off release build)
set -euo pipefail
cd "$(dirname "$0")/.."
export JAVA_HOME=/home/hatch/workspace/tools/jdk-21.0.13+9
export PATH="$JAVA_HOME/bin:$PATH"
CP=$(find ~/.m2/repository -name '*.jar' ! -name '*-aot.jar' \
       ! -path '*thheller/shadow-cljs/*' | tr '\n' ':')
CP="$CP$HOME/.m2/repository/thheller/shadow-cljs/3.5.11/shadow-cljs-3.5.11-aot.jar:src"
if [ $# -eq 0 ]; then
  exec java -cp "$CP" clojure.main -m shadow.cljs.devtools.cli watch app
else
  exec java -cp "$CP" clojure.main -m shadow.cljs.devtools.cli "$@"
fi
