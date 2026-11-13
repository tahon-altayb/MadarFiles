"""Prints the pictures the emulator tests saved (widgets, screens) into the job log, small, so they can be reviewed
from the log alone: EVIDENCE-PICTURE <name> <base64 jpeg or png>."""
import base64,glob,os,subprocess,sys
root=sys.argv[1] if len(sys.argv)>1 else 'app/build/outputs/connected_android_test_additional_output'
found=sorted(glob.glob(os.path.join(root,'**','*.png'),recursive=True))
print('EVIDENCE-PICTURES',len(found),flush=True)
for path in found:
    data=b''
    try:data=subprocess.run(['convert',path,'-resize','640x2600>','-quality','70','jpg:-'],capture_output=True,timeout=60).stdout
    except Exception:data=b''
    if len(data)<500:
        data=open(path,'rb').read()
        if len(data)>900000:print('EVIDENCE-PICTURE-SKIPPED',os.path.basename(path),len(data),flush=True);continue
    print('EVIDENCE-PICTURE',os.path.basename(path),base64.b64encode(data).decode(),flush=True)
