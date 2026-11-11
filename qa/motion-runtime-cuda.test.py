"""The generic CUDA runtime used on L4/A100 Colab GPUs (the T4 has its own build): download, hash,
extract, and the server binary plus CUDA backend load with only the GPU driver missing.

Colab gave the owner an L4 for the first time and the job stopped at DIRECTOR_GPU_SETUP; this
path had never run outside a T4. CI has no GPU, so it proves everything up to the driver."""
import os,re,shutil,subprocess,sys,tempfile
from pathlib import Path
source=Path(__file__).resolve().parents[1]/'app/src/main/assets/motion'
with tempfile.TemporaryDirectory(prefix='madar-runtime-') as tmp:
 job=Path(tmp)/'job';job.mkdir();shutil.copyfile(source/'director_runtime.py',job/'director_runtime.py')
 sys.path.insert(0,str(job));import director_runtime as R
 gpu=R.install('cuda');libraries=R.install('cudart');binary=next(gpu.rglob('llama-server'))
 paths=sorted({str(p.parent) for root in [gpu,libraries] for p in root.rglob('*.so*')})
 env=dict(os.environ,LD_LIBRARY_PATH=':'.join(paths))
 out=subprocess.run([str(binary),'--version'],env=env,capture_output=True,text=True,timeout=60);assert out.returncode==0,out.stderr[-500:]
 missing=re.findall(r'(\S+) => not found',subprocess.run(['ldd',str(next(gpu.rglob('libggml-cuda.so')))],env=env,capture_output=True,text=True).stdout)
 assert set(missing)<={'libcuda.so.1'},missing
 assert R.setup_code(ValueError('RUNTIME_HASH'))=='SETUP_RUNTIME_HASH'
print('PASS generic CUDA runtime installs, runs, and needs only the GPU driver')
