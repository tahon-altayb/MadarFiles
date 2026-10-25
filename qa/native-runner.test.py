"""Regression: native runner must preserve Gradle failures and collect reports afterward."""
from pathlib import Path
import os, subprocess, tempfile
root=Path(__file__).resolve().parents[1]
with tempfile.TemporaryDirectory() as directory:
    work=Path(directory); binary=work/'bin'; binary.mkdir(); log=work/'commands.log'
    for name,key in [('gradle','MADAR_FAKE_GRADLE_EXIT'),('adb','MADAR_FAKE_ADB_EXIT'),('python3','MADAR_FAKE_COLLECT_EXIT')]:
        path=binary/name
        path.write_text('#!/bin/sh\nprintf "%s\\n" "'+name+' $*" >> "$MADAR_TEST_COMMAND_LOG"\nexit "${'+key+':-0}"\n')
        path.chmod(0o700)
    for gradle,collector,expected in [(0,0,0),(1,0,1),(42,0,42),(0,1,1)]:
        log.write_text('')
        env=dict(os.environ, PATH=str(binary)+os.pathsep+os.environ.get('PATH',''), MADAR_TEST_COMMAND_LOG=str(log), MADAR_FAKE_GRADLE_EXIT=str(gradle), MADAR_FAKE_COLLECT_EXIT=str(collector))
        result=subprocess.run(['bash',str(root/'qa/run-native-documents.sh')],cwd=work,env=env,capture_output=True,text=True)
        assert result.returncode==expected, (gradle,collector,result.returncode,result.stderr)
        commands=log.read_text()
        assert 'adb pull' in commands and 'python3 qa/collect-table-document.py' in commands, commands
print('PASS native runner success/failure propagation and report collection in four scenarios')
