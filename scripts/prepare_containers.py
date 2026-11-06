"""Build LGPL-only FFmpeg remuxing and software audio decoding; file protocol only."""
from pathlib import Path
import os,urllib.request,tarfile,subprocess,hashlib,shutil,time
root=Path(__file__).resolve().parents[1];vendor=root/'app/vendor';vendor.mkdir(parents=True,exist_ok=True);archive=vendor/'ffmpeg-7.1.1.tar.xz'
expected='733984395e0dbbe5c046abda2dc49a5544e7e0e1e2366bba849222ae9e3a03b1'
def download_source(candidates,opener=None):
 opener=opener or urllib.request.urlopen
 # Reuse only byte-verified archives. A mirror cannot replace the pinned version.
 for url,path,checksum,prefix in candidates:
  if path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest()==checksum:return path,prefix
 deadline=time.monotonic()+120;last=None
 for url,path,checksum,prefix in candidates:
  temporary=path.with_suffix('.download');digest=hashlib.sha256();total=0
  try:
   with opener(url,timeout=20) as response,temporary.open('wb') as stream:
    while True:
     data=response.read(1024*1024)
     if not data:break
     total+=len(data)
     if total>64*1024*1024 or time.monotonic()>deadline:raise IOError('FFmpeg download exceeded its bound')
     digest.update(data);stream.write(data)
   if digest.hexdigest()!=checksum:raise IOError('FFmpeg archive checksum mismatch')
   temporary.replace(path);return path,prefix
  except (OSError,ValueError) as error:
   last=error
   if time.monotonic()>deadline:break
   print('Retrying the pinned FFmpeg source from the next official endpoint',flush=True)
  finally:temporary.unlink(missing_ok=True)
 raise IOError('Verified FFmpeg source unavailable from official endpoints') from last

# The project documents github.com/FFmpeg/FFmpeg as its official Git mirror.
# This commit is the peeled n7.1.1 tag; its separate archive hash is pinned too.
mirror_commit='db69d06eeeab4f46da15030a80d539efb4503ca8'
mirror_sha='173ba614d8636491106cba08d79db469b5dee4b479388bc4fbcead8ca1fb79b1'
archive,prefix=download_source([
 ('https://ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz',archive,expected,'ffmpeg-7.1.1'),
 ('https://www.ffmpeg.org/releases/ffmpeg-7.1.1.tar.xz',archive,expected,'ffmpeg-7.1.1'),
 ('https://codeload.github.com/FFmpeg/FFmpeg/tar.gz/'+mirror_commit,vendor/'ffmpeg-7.1.1-git.tar.gz',mirror_sha,'FFmpeg-'+mirror_commit),
])
print('FFmpeg source SHA256',hashlib.sha256(archive.read_bytes()).hexdigest())
source=vendor/'ffmpeg-7.1.1'
if not source.exists():
 with tarfile.open(archive) as t:t.extractall(vendor,filter='data')
 extracted=vendor/prefix
 if extracted!=source:extracted.rename(source)
if (source/'RELEASE').read_text().strip()!='7.1.1':raise ValueError('Unexpected FFmpeg source version')
ndk=Path(os.environ['ANDROID_HOME'])/'ndk/27.2.12479018/toolchains/llvm/prebuilt/linux-x86_64';tool=ndk/'bin'
for abi,target,arch in [('arm64-v8a','aarch64-linux-android','aarch64'),('x86_64','x86_64-linux-android','x86_64')]:
 if (vendor/('ffmpeg-'+abi)/'lib/libswresample.a').exists():continue
 build=vendor/('ffmpeg-build-'+abi);build.mkdir(exist_ok=True);prefix=vendor/('ffmpeg-'+abi)
 args=[str(source/'configure'),'--prefix='+str(prefix),'--target-os=android','--arch='+arch,'--enable-cross-compile','--cc='+str(tool/(target+'29-clang')),'--cxx='+str(tool/(target+'29-clang++')),'--ar='+str(tool/'llvm-ar'),'--ranlib='+str(tool/'llvm-ranlib'),'--nm='+str(tool/'llvm-nm'),'--strip='+str(tool/'llvm-strip'),'--sysroot='+str(ndk/'sysroot'),'--disable-doc','--disable-programs','--disable-network','--disable-autodetect','--disable-everything','--disable-avdevice','--disable-avfilter','--disable-postproc','--disable-swscale','--enable-swresample','--disable-gpl','--disable-nonfree','--enable-decoder=aac,ac3,eac3,dca,flac,alac,vorbis,opus,mp1,mp2,mp3,amrnb,amrwb,mlp,truehd,pcm_mulaw,pcm_alaw','--enable-small','--enable-pic','--enable-static','--disable-shared','--disable-asm','--disable-debug','--enable-protocol=file','--enable-demuxer=mov,matroska','--enable-muxer=mov,mp4,matroska,webm','--enable-bsf=aac_adtstoasc,extract_extradata,h264_mp4toannexb,hevc_mp4toannexb','--enable-parser=aac,h264,hevc,vp8,vp9,opus,av1']
 subprocess.run(args,cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','-j2'],cwd=build,check=True,stdout=subprocess.DEVNULL);subprocess.run(['make','install'],cwd=build,check=True,stdout=subprocess.DEVNULL)
shutil.copyfile(source/'COPYING.LGPLv2.1',root/'app/src/main/assets/FFMPEG-COPYING.txt')
