"""Build LGPL-only FFmpeg container remuxing libraries; no network protocols in runtime."""
from pathlib import Path
import os,urllib.request,tarfile,subprocess,hashlib,shutil
root=Path(__file__).resolve().parents[1];vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True);archive=vendor/'ffmpeg-7.1.1.tar.xz'
if not archive.exists():urllib.request.urlretrieve('https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz',archive)
print('FFmpeg source SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
source=vendor/'ffmpeg-7.1.1'
if not source.exists():
 with tarfile.open(archive) as t:t.extractall(vendor,filter='data')
ndk=Path(os.environ['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64';tool=ndk/'bin'
for abi,target,arch in [('arm64-v8a','aarch64-linux-android','aarch64'),('x86_64','x86_64-linux-android','x86_64')]:
 if (vendor/('ffmpeg-'+abi)/'lib/libavformat.a').exists():continue
 build=vendor/('ffmpeg-build-'+abi);build.mkdir(exist_ok=True);prefix=vendor/('ffmpeg-'+abi)
 args=[str(source/'configure'),'--prefix='+str(prefix),'--target-os=android','--arch='+arch,'--enable-cross-compile','--cc='+str(tool/(target+'29-clang')),'--cxx='+str(tool/(target+'29-clang++')),'--ar='+str(tool/'llvm-ar'),'--ranlib='+str(tool/'llvm-ranlib'),'--nm='+str(tool/'llvm-nm'),'--strip='+str(tool/'llvm-strip'),'--sysroot='+str(ndk/'sysroot'),'--disable-doc','--disable-programs','--disable-network','--disable-autodetect','--disable-everything','--disable-avdevice','--disable-avfilter','--disable-postproc','--disable-swscale','--disable-swresample','--enable-small','--enable-pic','--enable-static','--disable-shared','--disable-asm','--disable-debug','--enable-protocol=file','--enable-demuxer=mov,matroska','--enable-muxer=mov,mp4,matroska,webm','--enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb','--enable-parser=aac,h264,hevc,vp8,vp9,opus,av1']
 subprocess.run(args,cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','-j2'],cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','install'],cwd=build,check=True,stdout=subprocess.DEVNULL)
shutil.copyfile(source/'COPYING.LGPLv2.1',root/'app/src/main/assets/FFMPEG-COPYING.txt')
