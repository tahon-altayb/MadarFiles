"""Build the LGPL LAME encoder from its Debian upstream source archive for Android."""
from pathlib import Path
import os,urllib.request,tarfile,subprocess,shutil,hashlib
root=Path(__file__).resolve().parents[1];vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True)
archive=vendor/'lame-3.100.tar.gz'
if not archive.exists():urllib.request.urlretrieve('https://deb.debian.org/debian/pool/main/l/lame/lame_3.100.orig.tar.gz',archive)
print('LAME source SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
source=vendor/'lame-3.100'
if not source.exists():
 with tarfile.open(archive) as t:t.extractall(vendor,filter='data')
ndk=Path(os.environ['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64/bin'
for abi,target in [('arm64-v8a','aarch64-linux-android'),('x86_64','x86_64-linux-android')]:
 build=vendor/('lame-build-'+abi);build.mkdir(exist_ok=True);env=os.environ.copy();env.update(CC=str(ndk/(target+'29-clang')),AR=str(ndk/'llvm-ar'),RANLIB=str(ndk/'llvm-ranlib'),CFLAGS='-O3 -fPIC',LDFLAGS='')
 subprocess.run([str(source/'configure'),'--host='+target,'--disable-shared','--enable-static','--disable-frontend','--disable-decoder','--with-pic'],cwd=build,env=env,check=True,stdout=subprocess.DEVNULL)
 subprocess.run(['make','-j2'],cwd=build,env=env,check=True,stdout=subprocess.DEVNULL)
 dest=vendor/abi;dest.mkdir(exist_ok=True);shutil.copyfile(build/'libmp3lame/.libs/libmp3lame.a',dest/'libmp3lame.a')
shutil.copyfile(source/'include/lame.h',vendor/'lame.h');shutil.copyfile(source/'COPYING',root/'app/src/main/assets/LAME-COPYING.txt')
