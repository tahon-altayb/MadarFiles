#!/bin/bash
# How long yt-dlp takes to read a YouTube page with each player client, and which formats each gives.
python3 -m pip install -q -U yt-dlp >/dev/null 2>&1; yt-dlp --version
URL="https://www.youtube.com/watch?v=jNQXAC9IVRw"; URL2="https://www.youtube.com/watch?v=dQw4w9WgXcQ"
for u in "$URL" "$URL2"; do
 for client in default android_vr "android_vr,web_safari" tv ios mweb; do
  start=$(date +%s.%N)
  if [ "$client" = default ]; then out=$(yt-dlp -J --no-warnings "$u" 2>&1); else out=$(yt-dlp -J --no-warnings --extractor-args "youtube:player_client=$client" "$u" 2>&1); fi
  end=$(date +%s.%N)
  echo "== $client $(echo "$end - $start" | bc) s"
  echo "$out" | python3 -c "
import json,sys
t=sys.stdin.read()
try:d=json.loads(t)
except Exception:print('   ERROR',t[:300].replace(chr(10),' '));sys.exit()
f=d.get('formats',[])
v=sorted({(x.get('height'),(x.get('vcodec') or '')[:4]) for x in f if x.get('vcodec') not in (None,'none') and x.get('height')})
a=sorted({(x.get('ext'),(x.get('acodec') or '')[:4]) for x in f if x.get('vcodec')=='none'})
print('   video',v);print('   audio',a)"
 done
done
# CPU cost of the default client: user time
/usr/bin/time -f "default CPU user %U s, system %S s, wall %e s" yt-dlp -J --no-warnings "$URL2" >/dev/null
/usr/bin/time -f "android_vr CPU user %U s, system %S s, wall %e s" yt-dlp -J --no-warnings --extractor-args "youtube:player_client=android_vr" "$URL2" >/dev/null
