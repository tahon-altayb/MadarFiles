#!/usr/bin/env bash
set -u
mkdir -p tail-results
MADAR_TAIL_FAILURE=0
for iteration in 1 2 3 4 5; do
  adb shell am instrument -w -r -e class 'com.madar.files.PlayerWatchTest#rollingAudioWaitsForReserveAndResumesWithoutSkippingTheTail' com.madar.files.validation.test/androidx.test.runner.AndroidJUnitRunner > "tail-results/tail-$iteration.txt"
  cat "tail-results/tail-$iteration.txt"
  python3 -c "import re,sys; s=open(sys.argv[1]).read(); assert re.search(r'OK\s*\(1 test\)',s) and not re.search(r'INSTRUMENTATION_FAILED|FAILURES!!!',s)" "tail-results/tail-$iteration.txt" || MADAR_TAIL_FAILURE=1
done
exit "$MADAR_TAIL_FAILURE"
