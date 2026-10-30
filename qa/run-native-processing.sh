#!/usr/bin/env bash
# Keep the language-model test in a fresh process, without media/OCR native caches.
set -uo pipefail
mkdir -p phone-screenshots || exit $?
# A fresh emulator's full-screen education window steals focus from PiP.
# Acknowledge that one-time OS tutorial, as a real user would, before UI tests.
# This changes only the disposable test device; application behavior is unchanged.
adb shell settings put secure immersive_mode_confirmations confirmed || exit $?
gradle --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" --max-workers=2 :app:installDebug :app:installDebugAndroidTest || exit $?
status=0
# Exercise lifecycle transitions first and in a fresh process. A fixture/lifecycle
# failure must not spend another fifteen minutes running unrelated model tests.
adb shell am force-stop com.madar.files || exit $?
adb logcat -c
adb logcat -v threadtime > phone-screenshots/player-lifecycle-logcat.txt 2>&1 &
lifecycle_logcat_pid=$!
adb shell am instrument -w -r -e class com.madar.files.PlayerLifecycleTest com.madar.files.test/androidx.test.runner.AndroidJUnitRunner > phone-screenshots/player-lifecycle-tests.txt 2>&1 || status=$?
kill "$lifecycle_logcat_pid" 2>/dev/null || true
wait "$lifecycle_logcat_pid" 2>/dev/null || true
cat phone-screenshots/player-lifecycle-tests.txt
adb pull /data/local/tmp/madar-pip-controls.png phone-screenshots/pip-controls.png || true
adb pull /data/local/tmp/madar-watch-choice.png phone-screenshots/watch-choice.png || true
python3 - <<'PYLIFECYCLE'
from pathlib import Path
import re
log=Path('phone-screenshots/player-lifecycle-tests.txt').read_text(errors='replace')
assert re.search(r'\bOK\s*\(15 tests\)',log), 'All fifteen real player lifecycle tests must pass'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-\d+',log), 'Failed or skipped lifecycle tests'
assert 'INSTRUMENTATION_FAILED' not in log and 'FAILURES!!!' not in log
PYLIFECYCLE
if [ "$?" -ne 0 ] || [ "$status" -ne 0 ]; then
 tail -n 350 phone-screenshots/player-lifecycle-logcat.txt
 exit 1
fi
adb shell am force-stop com.madar.files || exit $?
adb logcat -c
adb logcat -v threadtime > phone-screenshots/player-source-logcat.txt 2>&1 &
source_logcat_pid=$!
adb shell am instrument -w -r -e class com.madar.files.PlayerSourceTest com.madar.files.test/androidx.test.runner.AndroidJUnitRunner > phone-screenshots/player-source-tests.txt 2>&1 || status=$?
kill "$source_logcat_pid" 2>/dev/null || true
wait "$source_logcat_pid" 2>/dev/null || true
cat phone-screenshots/player-source-tests.txt
python3 - <<'PYSOURCE'
from pathlib import Path
import re
log=Path('phone-screenshots/player-source-tests.txt').read_text(errors='replace')
assert re.search(r'\bOK\s*\(8 tests\)',log), 'All actual source and software codec tests must pass'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-\d+',log), 'Failed or skipped player source tests'
assert 'INSTRUMENTATION_FAILED' not in log and 'FAILURES!!!' not in log
PYSOURCE
if [ "$?" -ne 0 ] || [ "$status" -ne 0 ]; then
 tail -n 350 phone-screenshots/player-source-logcat.txt
 exit 1
fi

adb shell am force-stop com.madar.files || status=1
adb logcat -c
# Public model bytes are host-cached and SHA-verified; actual install validation and
# inference still run on Android. A transient emulator DNS failure is not a model test.
if [ -n "${MADAR_CI_AGENT_MODEL:-}" ]; then
 [ -f "$MADAR_CI_AGENT_MODEL" ] || exit 1
 adb push "$MADAR_CI_AGENT_MODEL" /data/local/tmp/madar-ci-agent.litertlm || exit $?
 adb shell run-as com.madar.files mkdir -p files || exit $?
 adb shell run-as com.madar.files cp /data/local/tmp/madar-ci-agent.litertlm files/agent-qwen3-1.7b.litertlm || exit $?
 adb shell rm -f /data/local/tmp/madar-ci-agent.litertlm || exit $?
fi
adb logcat -v threadtime > phone-screenshots/local-agent-logcat.txt 2>&1 &
logcat_pid=$!
adb shell am instrument -w -r -e class com.madar.files.LocalAgentRuntimeTest com.madar.files.test/androidx.test.runner.AndroidJUnitRunner > phone-screenshots/local-agent-tests.txt 2>&1 || status=$?
kill "$logcat_pid" 2>/dev/null || true
wait "$logcat_pid" 2>/dev/null || true
cat phone-screenshots/local-agent-tests.txt
python3 - <<'PY'
from pathlib import Path
import re
log=Path('phone-screenshots/local-agent-tests.txt').read_text(errors='replace')
assert re.search(r'\bOK\s*\(2 tests\)',log), 'Both real local-agent tests must pass'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-\d+',log), 'Failed or skipped local-agent tests'
assert 'INSTRUMENTATION_FAILED' not in log and 'FAILURES!!!' not in log, 'Local-agent instrumentation failed'
PY
if [ "$?" -ne 0 ]; then status=1; fi
if [ "$status" -ne 0 ]; then exit "$status"; fi
adb shell am force-stop com.madar.files || status=1
adb logcat -c
adb logcat -v threadtime > phone-screenshots/player-watch-logcat.txt 2>&1 &
watch_logcat_pid=$!
adb shell am instrument -w -r -e class com.madar.files.PlayerWatchTest com.madar.files.test/androidx.test.runner.AndroidJUnitRunner > phone-screenshots/player-watch-tests.txt 2>&1 || status=$?
kill "$watch_logcat_pid" 2>/dev/null || true
wait "$watch_logcat_pid" 2>/dev/null || true
cat phone-screenshots/player-watch-tests.txt
python3 - <<'PYWATCH'
from pathlib import Path
import re
log=Path('phone-screenshots/player-watch-tests.txt').read_text(errors='replace')
assert re.search(r'\bOK\s*\(8 tests\)',log), 'All real player-watch tests must pass'
assert not re.search(r'INSTRUMENTATION_STATUS_CODE:\s*-\d+',log), 'Failed or skipped player-watch tests'
assert 'INSTRUMENTATION_FAILED' not in log and 'FAILURES!!!' not in log, 'Player-watch instrumentation failed'
PYWATCH
if [ "$?" -ne 0 ]; then
 status=1
 tail -n 350 phone-screenshots/player-watch-logcat.txt
 exit "$status"
fi
adb shell am force-stop com.madar.files || status=1
gradle --no-daemon -Dorg.gradle.jvmargs="-Xmx1536m -Dfile.encoding=UTF-8" --max-workers=2 :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.notClass=com.madar.files.PlayerLifecycleTest,com.madar.files.LocalAgentRuntimeTest,com.madar.files.PlayerWatchTest,com.madar.files.PlayerSourceTest || status=$?
exit "$status"
