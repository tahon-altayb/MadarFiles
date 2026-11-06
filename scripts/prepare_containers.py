"""Build LGPL-only FFmpeg remuxing and software audio decoding; file protocol only."""
from pathlib import Path
import os,urllib.request,tarfile,subprocess,hashlib,shutil,time
root=Path(__file__).resolve().parents[1];vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True);archive=vendor/'ffmpeg-7.1.1.tar.xz'
expected='733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1'
if not archive.exists() or hashlib.sha256(archive.read_bytes()).hexdigest()!=expected:
 temporary=archive.with_suffix('.download');deadline=time.monotonic()+150
 for attempt in range(3):
  try:
   host='ffmpeg.org' if attempt%2==0 else 'www.ffmpeg.org';digest=hashlib.sha256();total=0
   with urllib.request.urlopen('https://'+host+'/releases/ffmpeg-7.1.1.tar.xz',timeout=30) as response,temporary.open('wb') as stream:
    while True:
     data=response.read(1024*1024)
     if not data:break
     total+=len(data)
     if total>64*1024*1024 or time.monotonic()>deadline:raise IOError('FFmpeg download exceeded its bound')
     digest.update(data);stream.write(data)
   if digest.hexdigest()!=expected:raise IOError('FFmpeg archive checksum mismatch')
   temporary.replace(archive);break
  except (OSError,ValueError):
   if attempt==2 or time.monotonic()>deadline:raise
   print('Retrying official FFmpeg source download',attempt+1,flush=True);time.sleep(attempt+1)
  finally:temporary.unlink(missing_ok=True)
print('FFmpeg source SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
source=vendor/'ffmpeg-7.1.1'
if not source.exists():
 with tarfile.open(archive) as t:t.extractall(vendor,filter='data')
ndk=Path(os.environ['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64';tool=ndk/'bin'
for abi,target,arch in [('arm64-v8a','aarch64-linux-android','aarch64'),('x86_64','x86_64-linux-android','x86_64')]:
 if (vendor/('ffmpeg-'+abi)/'lib/libswresample.a').exists():continue
 build=vendor/('ffmpeg-build-'+abi);build.mkdir(exist_ok=True);prefix=vendor/('ffmpeg-'+abi)
 args=[str(source/'configure'),'--prefix='+str(prefix),'--target-os=android','--arch='+arch,'--enable-cross-compile','--cc='+str(tool/(target+'29-clang')),'--cxx='+str(tool/(target+'29-clang++')),'--ar='+str(tool/'llvm-ar'),'--ranlib='+str(tool/'llvm-ranlib'),'--nm='+str(tool/'llvm-nm'),'--strip='+str(tool/'llvm-strip'),'--sysroot='+str(ndk/'sysroot'),'--disable-doc','--disable-programs','--disable-network','--disable-autodetect','--disable-everything','--disable-avdevice','--disable-avfilter','--disable-postproc','--disable-swscale','--enable-swresample','--disable-gpl','--disable-nonfree','--enable-decoder=aac,ac3,eac3,dca,flac,alac,vorbis,opus,mp1,mp2,mp3,amrnb,amrwb,mlp,truehd,pcm_mulaw,pcm_alaw','--enable-small','--enable-pic','--enable-static','--disable-shared','--disable-asm','--disable-debug','--enable-protocol=file','--enable-demuxer=mov,matroska','--enable-muxer=mov,mp4,matroska,webm','--enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb','--enable-parser=aac,h264,hevc,vp8,vp9,opus,av1']
 subprocess.run(args,cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','-j2'],cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','install'],cwd=build,check=True,stdout=subprocess.DEVNULL)
shutil.copyfile(source/'COPYING.LGPLv2.1',root/'app/src/main/assets/FFMPEG-COPYING.txt')
