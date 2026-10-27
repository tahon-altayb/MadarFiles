"""All isolated model/player tests and the remaining suite must pass."""
import os
from pathlib import Path
import subprocess
import tempfile

script=Path('qa/run-native-processing.sh').resolve()
for isolated,watch,rest,expect in [('pass','pass','0',0),('fail','pass','0',1),('pass','fail','0',1),('pass','pass','7',7),('skip','pass','0',1),('pass','skip','0',1)]:
 with tempfile.TemporaryDirectory() as temp:
  root=Path(temp);bin=root/'bin';bin.mkdir()
  adb=bin/'adb';adb.write_text('''#!/usr/bin/env bash
if [[ "$*" == *"am instrument"* ]]; then
 result="$MADAR_FAKE_ISOLATED"
 count=2
 if [[ "$*" == *"PlayerWatchTest"* ]]; then result="$MADAR_FAKE_WATCH";count=7;fi
 if [ "$result" = fail ]; then echo 'FAILURES!!!'; elif [ "$result" = skip ]; then printf 'INSTRUMENTATION_STATUS_CODE: -3\\nOK (%s tests)\\n' "$count"; else printf 'INSTRUMENTATION_STATUS_CODE: 0\\nOK (%s tests)\\n' "$count"; fi
fi
''');adb.chmod(0o700)
  gradle=bin/'gradle';gradle.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> calls.txt
if [[ "$*" == *connectedDebugAndroidTest* ]]; then exit "$MADAR_FAKE_REST"; fi
''');gradle.chmod(0o700)
  env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'],MADAR_FAKE_ISOLATED=isolated,MADAR_FAKE_WATCH=watch,MADAR_FAKE_REST=rest)
  result=subprocess.run(['bash',str(script)],cwd=root,env=env,capture_output=True,text=True)
  assert result.returncode==expect,(isolated,watch,rest,result.returncode,result.stderr)
  calls=(root/'calls.txt').read_text()
  assert ':app:installDebugAndroidTest' in calls and 'notClass=com.madar.files.LocalAgentRuntimeTest,com.madar.files.PlayerWatchTest' in calls
  assert (root/'phone-screenshots/local-agent-tests.txt').is_file()
  assert (root/'phone-screenshots/player-watch-tests.txt').is_file()
print('PASS: local model, real player/watch and remaining tests retain every failure or skip')
