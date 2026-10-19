"""Reuse the hosted runner SDK; install only absent pinned packages with a bounded wait."""
import os,subprocess
from pathlib import Path
root=Path(os.environ['ANDROID_HOME']);manager=root/'cmdline-tools/latest/bin/sdkmanager'
required={'platform-tools':root/'platform-tools/adb','platforms;android-35':root/'platforms/android-35/android.jar','build-tools;35.0.0':root/'build-tools/35.0.0/aapt2','ndk;27.2.12479018':root/'ndk/27.2.12479018/source.properties','cmake;3.22.1':root/'cmake/3.22.1/bin/cmake'}
missing=[name for name,path in required.items() if not path.is_file()]
print('Preinstalled packages:',[name for name,path in required.items() if path.is_file()],flush=True)
if missing:
 print('Installing missing packages:',missing,flush=True)
 subprocess.run([str(manager),'--licenses'],input='y\n'*100,text=True,stdout=subprocess.DEVNULL,check=True,timeout=90)
 subprocess.run([str(manager),'--install',*missing],check=True,timeout=480)
with open(os.environ['GITHUB_PATH'],'a') as out:
 for path in [manager.parent,root/'platform-tools',root/'emulator']:out.write(str(path)+'\n')
