#!/usr/bin/env bash
# Raises one defect at a time (others at a mild baseline) to find where the scanner starts to fail. Usage: ramp.sh knob "0.8 1.2 1.6 2" [labels]
knob=$1; levels=$2; n=${3:-120}; here=$(dirname "$0")
for v in $levels; do
  args="--warp 0.3 --dirt 0.3 --soft 0.3 --fade 0.3 --streak 0.3 --feed 0.3 --light 0.3"
  args="${args/--$knob 0.3/--$knob $v}"
  printf '%-7s %-4s ' "$knob" "$v"
  python3 "$here/bench.py" --labels "$n" --seed0 11000 $args 2>&1 | head -3 | tr '\n' ' ' | sed -E 's/settings:.*region=1//; s/render.*ML Kit median [0-9]+ ms//'
  echo
done
