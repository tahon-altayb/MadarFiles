"""The two instrumentation groups must both pass; failures cannot be hidden by later commands."""
import os
from pathlib import Path
import subprocess
import tempfile

script=Path('qa/run-native-processing.sh').resolve()
for isolated,rest,expect in [('pass','0',0),('fail','0',1),('pass','7',7),('skip','0',1)]:
 with tempfile.TemporaryDirectory() as temp:
  root=Path(temp);bin=root/'bin';bin.mkdir()
  adb=bin/'adb';adb.write_text('''#!/usr/bin/env bash
if [[ "$*" == *"am instrument"* ]]; then
 if [ "$MADAR_FAKE_ISOLATED" = fail ]; then echo 'FAILURES!!!'; elif [ "$MADAR_FAKE_ISOLATED" = skip ]; then printf 'INSTRUMENTATION_STATUS_CODE: -3\\nOK (2 tests)\\n'; else printf 'INSTRUMENTATION_STATUS_CODE: 0\\nOK (2 tests)\\n'; fi
fi
''');adb.chmod(0o700)
  gradle=bin/'gradle';gradle.write_text('''#!/usr/bin/env bash
printf '%s\\n' "$*" >> calls.txt
if [[ "$*" == *connectedDebugAndroidTest* ]]; then exit "$MADAR_FAKE_REST"; fi
''');gradle.chmod(0o700)
  env=dict(os.environ,PATH=str(bin)+':'+os.environ['PATH'],MADAR_FAKE_ISOLATED=isolated,MADAR_FAKE_REST=rest)
  result=subprocess.run(['bash',str(script)],cwd=root,env=env,capture_output=True,text=True)
  assert result.returncode==expect,(isolated,rest,result.returncode,result.stderr)
  calls=(root/'calls.txt').read_text();assert ':app:installDebugAndroidTest' in calls and '-Pandroid.testInstrumentationRunnerArguments.notClass=com.madar.files.LocalAgentRuntimeTest' in calls
  assert (root/'phone-screenshots/local-agent-tests.txt').is_file()
print('PASS: isolated native-model and remaining tests both required; failures and skips retained')
