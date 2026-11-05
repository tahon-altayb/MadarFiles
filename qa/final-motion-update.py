"""Asset-only update of an already fully verified APK, signed with its original key."""
import os,json,hashlib,base64,subprocess,zipfile,shutil,urllib.request
from pathlib import Path
BASE='33f35811add606e2b837802d4f3ccf9ea121d459';RUN=41935874600
headers={'Authorization':'Bearer '+os.environ['GH_TOKEN'],'Accept':'application/vnd.github+json'}
with urllib.request.urlopen(urllib.request.Request(f'https://api.github.com/repos/tahon-altayb/MadarFiles/actions/runs/{RUN}',headers=headers)) as f:run=json.load(f)
assert run['head_sha']==BASE and run['conclusion']=='success','The base APK must have passed every release and device check'
changed=set(subprocess.check_output(['git','diff','--name-only',BASE,'HEAD','--','app'],text=True).splitlines())
assert changed=={'app/src/main/assets/motion/director.py'},changed
apk=list(Path('base-release').rglob('*.apk'));assert len(apk)==1;apk=apk[0]
bt=Path(os.environ['ANDROID_HOME'])/'build-tools/35.0.0'
if not (bt/'apksigner').exists():
 manager=Path(os.environ['ANDROID_HOME'])/'cmdline-tools/latest/bin/sdkmanager'
 subprocess.run([str(manager),'--licenses'],input='y\n'*100,text=True,stdout=subprocess.DEVNULL,check=True,timeout=90)
 subprocess.run([str(manager),'--install','build-tools;35.0.0'],check=True,timeout=480)
subprocess.run(['python3','scripts/verify_release.py',str(apk),'--version-code','57','--version-name','1.9.22'],check=True)
key=Path('.signing/original.keystore');key.parent.mkdir(mode=0o700,exist_ok=True);key.write_bytes(base64.b64decode(os.environ['MADAR_KEYSTORE_BASE64'],validate=True));key.chmod(0o600)
cert=subprocess.check_output(['keytool','-exportcert','-keystore',str(key),'-alias',os.environ['MADAR_KEY_ALIAS'],'-storepass:env','MADAR_STORE_PASSWORD'])
assert hashlib.sha256(cert).hexdigest()=='753b13a984815b725b41a8f2ae2ef07d9d9e8cc64811cbff004d5da8ccedfb5e'
asset='assets/motion/director.py';replacement=Path('app/src/main/assets/motion/director.py').read_bytes()
def signature(name):return name.upper().startswith('META-INF/') and (name.upper().endswith(('.RSA','.SF','.DSA','.EC')) or name.upper()=='META-INF/MANIFEST.MF')
with zipfile.ZipFile(apk) as old,zipfile.ZipFile('unsigned.apk','w') as new:
 for info in old.infolist():
  if signature(info.filename):continue
  if info.filename==asset:new.writestr(info,replacement)
  else:
   with old.open(info) as source,new.open(info,'w') as dest:shutil.copyfileobj(source,dest,1024*1024)
subprocess.run([str(bt/'zipalign'),'-f','-P','16','4','unsigned.apk','aligned.apk'],check=True)
final='MadarFiles-1.9.22-update.apk'
subprocess.run([str(bt/'apksigner'),'sign','--ks',str(key),'--ks-key-alias',os.environ['MADAR_KEY_ALIAS'],'--ks-pass','env:MADAR_STORE_PASSWORD','--key-pass','env:MADAR_KEY_PASSWORD','--v1-signing-enabled','false','--v2-signing-enabled','true','--v3-signing-enabled','true','--v4-signing-enabled','false','--out',final,'aligned.apk'],check=True)
subprocess.run([str(bt/'zipalign'),'-c','-P','16','4',final],check=True)
subprocess.run(['python3','scripts/verify_release.py',final,'--version-code','57','--version-name','1.9.22'],check=True)
count=0
with zipfile.ZipFile(apk) as old,zipfile.ZipFile(final) as new:
 for info in old.infolist():
  if signature(info.filename):continue
  assert new.read(info.filename)==(replacement if info.filename==asset else old.read(info.filename)),info.filename
  count+=1
proof={'base_verified_commit':BASE,'base_release_run':RUN,'source_asset_sha256':hashlib.sha256(replacement).hexdigest(),'unchanged_payload_verified':count-1,'apk_sha256':hashlib.sha256(Path(final).read_bytes()).hexdigest(),'original_certificate_verified':True,'package':'com.madar.files','version':'1.9.22 (57)'}
Path('release-proof.json').write_text(json.dumps(proof,indent=2));print(json.dumps(proof))

