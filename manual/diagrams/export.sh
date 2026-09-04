#!/bin/sh
# Exports every draw.io source in this directory to an SVG under manual/modules/ROOT/images/.
# The exporter is the draw.io desktop app (https://www.drawio.com), on the path as `drawio`;
# a machine without a display runs it under xvfb-run.
set -e
cd "$(dirname "$0")"
out=../modules/ROOT/images
run=
if [ -z "$DISPLAY" ] && command -v xvfb-run >/dev/null; then
  run="xvfb-run -a"
fi
for f in *.drawio; do
  $run drawio -x -f svg -b 10 --embed-svg-fonts false -o "$out/${f%.drawio}.svg" "$f" 2>/dev/null | grep -- '->'
done
