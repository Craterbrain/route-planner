#!/usr/bin/env bash
# One defect at a time (the others off) at the given strength, to see which slider the scanner struggles with.
#   tools/label-lab/sweep.sh 1.2 120
s=${1:-1.2}; n=${2:-120}; here=$(dirname "$0")
for knob in fade streak feed warp dirt soft; do
  args="--warp 0 --dirt 0 --soft 0 --fade 0 --streak 0 --feed 0"
  args="${args/--$knob 0/--$knob $s}"
  printf '%-7s %s  ' "$knob" "$s"
  python3 "$here/bench.py" --labels "$n" --seed0 9000 $args 2>&1 | head -3 | tr '\n' ' ' | sed -E 's/settings:.*region=1//; s/render.*ML Kit median/ML Kit median/'
  echo
done
