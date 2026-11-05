"""Pinned llama runtimes. Cloud planning requires verified CUDA inference."""
import hashlib,json,math,os,re,shutil,subprocess,tarfile,time,urllib.request
from pathlib import Path
ROOT=Path(__file__).resolve().parent
TAG='b12089'
ASSETS={
 'cpu':('llama-b12089-bin-ubuntu-x64.tar.gz','f3cd7d5d34dee97be7137b749723ac10228055b85336fb56f1d5d0c80ebb737d',128*1024**2),
 'cuda':('llama-b12089-bin-ubuntu-cuda-12.8-x64.tar.gz','b6b707a2e654f2f777f8348d19096b6944f0a549b514b31b8c40e98edbae1233',256*1024**2),
 'cudart':('cudart-llama-b12089-bin-ubuntu-cuda-12.8-x64.tar.gz','4c8c6c78b1e868d1fb2150a65cd64e11b77da127fae82b6b26a96d94a8d65e8a',768*1024**2)}

def gpu_eligible(text):
 try:
  memory,driver=text.strip().splitlines()[0].split(',')
  version=tuple(int(n) for n in driver.strip().split('.'));version=version+(0,)*(3-len(version))
  return int(memory.strip())>=11000 and version>=(525,60,13)
 except (ValueError,IndexError):return False

def gpu_available():
 if not shutil.which('nvidia-smi'):return False
 try:
  r=subprocess.run(['nvidia-smi','--query-gpu=memory.free,driver_version','--format=csv,noheader,nounits'],capture_output=True,text=True,timeout=10)
  return r.returncode==0 and gpu_eligible(r.stdout)
 except (OSError,subprocess.TimeoutExpired):return False

def threads():
 try:n=len(os.sched_getaffinity(0))
 except (AttributeError,OSError):n=os.cpu_count() or 2
 try:
  quota,period=Path('/sys/fs/cgroup/cpu.max').read_text().split()
  if quota!='max':n=min(n,max(1,math.ceil(int(quota)/int(period))))
 except (OSError,ValueError):pass
 return max(1,min(8,n))

def install(kind):
 name,digest,maximum=ASSETS[kind];target=ROOT.parent/('madar-llama-'+TAG+'-'+kind);marker=target/'.verified'
 if not marker.is_file() or marker.read_text()!=digest:
  target.mkdir(parents=True,exist_ok=True);archive=target/'runtime.tar.gz';temporary=target/'runtime.part';end=time.monotonic()+180
  h=hashlib.sha256();total=0
  try:
   with urllib.request.urlopen('https://github.com/ggml-org/llama.cpp/releases/download/'+TAG+'/'+name,timeout=30) as r,temporary.open('wb') as w:
    while True:
     b=r.read(1024*1024)
     if not b:break
     total+=len(b)
     if total>maximum:raise ValueError('RUNTIME_SIZE')
     if time.monotonic()>end:raise ValueError('RUNTIME_DOWNLOAD_TIMEOUT')
     h.update(b);w.write(b)
   if h.hexdigest()!=digest:raise ValueError('RUNTIME_HASH')
   temporary.replace(archive)
   with tarfile.open(archive) as tar:tar.extractall(target,filter='data')
   marker.write_text(digest)
  finally:temporary.unlink(missing_ok=True)
 return target

def options():
 # CPU inference is an explicit test-only option, never a cloud fallback.
 qa_cpu=os.environ.get('MADAR_MOTION_QA_CPU')=='1' and os.environ.get('MADAR_MOTION_QA')=='1'
 if qa_cpu:
  cpu=install('cpu');binary=next(cpu.rglob('llama-server'),None)
  if binary is None:raise ValueError('RUNTIME_MISSING')
  return [{'binary':binary,'env':os.environ.copy(),'gpu':False}]
 if gpu_available():
  try:
   gpu=install('cuda');libraries=install('cudart');binary=next(gpu.rglob('llama-server'))
   paths=sorted({str(p.parent) for root in [gpu,libraries] for p in root.rglob('*.so*')})
   env=os.environ.copy();env['LD_LIBRARY_PATH']=':'.join(paths+[env.get('LD_LIBRARY_PATH','')])
   return [{'binary':binary,'env':env,'gpu':True}]
  except (OSError,ValueError,StopIteration,tarfile.TarError) as error:
   raise ValueError('DIRECTOR_GPU_SETUP') from error
 raise ValueError('DIRECTOR_GPU_REQUIRED')

def offloaded(log):
 matches=re.findall(r'offloaded\s+(\d+)/(\d+)\s+layers\s+to\s+GPU',log,re.I)
 return bool(matches and any(int(a)>0 and int(a)==int(b) for a,b in matches))
