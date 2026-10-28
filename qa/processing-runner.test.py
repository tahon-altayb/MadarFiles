"""All isolated model/player tests and the remaining suite must pass."""
import os
from pathlib import Path
import subprocess
import tempfile

script=Path('qa/run-native-processing.sh').resolve()
for source,isolated,watch,rest,expect in [('pass','pass','pass','0',0),('fail','pass','pass','0',1),('skip','pass','pass','0',1),('pass','fail','pass','0',1),('pass','pass','fail','0',1),('pass','pass','pass','7',7),('pass','skip','pass','0',1),('pass','pass','skip','0',1)]:
 with tempfile.TemporaryDirectory() as temp:
  root=Path(temp);bin=root/'bin';bin.mkdir()
  adb=bin/'adb';adb.write_text('''#!/usr/bin/env bash
if [[ "$*" == *"am instrument"* ]]; then
 result="$MADAR_FAKE_ISOLATED"
 count=2
 if [[ "$*" == *"PlayerWatchTest"* ]]; then result="$MADAR_FAKE_WATCH";count=8;fi
 if [[ "$*" == *"PlayerSourceTest"* ]]; then result="$MADAR_FAKE_SOURCE";count=5;fi
 if [ "$result" = fail ]; then echo 'FAILURES!!!'; elif [ "$result" = skip ]; then printf 'INSTRUMENTATION_STATUS_CODE: -3\\nOK (%s tests)\\n' "$count"; else printf 'INSTRUMENTATION_STATUS_CODE: 0\\nOK (%s tests)\\n' "$count"; fi
fi
''');adb.chmod(0o700)
  gradle=bin/'gradle';gradle.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> calls.txt
if [[ "$*" == *connectedDebugAndroidTest* ]]; then exit "$MADAR_FAKE_REST"; fi
''');gradle.chmod(0o700)
  env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'],MADAR_FAKE_SOURCE=source,MADAR_FAKE_ISOLATED=isolated,MADAR_FAKE_WATCH=watch,MADAR_FAKE_REST=rest)
  result=subprocess.run(['bash',str(script)],cwd=root,env=env,capture_output=True,text=True)
  assert result.returncode==expect,(source,isolated,watch,rest,result.returncode,result.stderr)
  calls=(root/'calls.txt').read_text()
  assert ':app:installDebugAndroidTest' in calls
  assert ('notClass=com.madar.files.LocalAgentRuntimeTest,com.madar.files.PlayerWatchTest,com.madar.files.PlayerSourceTest' in calls)==(source=='pass' and isolated=='pass' and watch=='pass')
  assert (root/'phone-screenshots/player-source-tests.txt').is_file()
  assert (root/'phone-screenshots/local-agent-tests.txt').is_file()==(source=='pass')
  assert (root/'phone-screenshots/player-watch-tests.txt').is_file()==(source=='pass' and isolated=='pass')
print('PASS: local model, real player/watch and remaining tests retain every failure or skip')
