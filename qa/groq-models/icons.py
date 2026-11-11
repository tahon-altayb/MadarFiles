# Which App Store entries are the official apps? Prints id, name, seller and bundle for each search (no key needed).
import json,urllib.request,urllib.parse,time
TERMS=['HungerStation','Keeta','Jahez','Mrsool','ToYou','The Chefz','Ninja grocery','Careem','Talabat','Shgardi','Noon','TikTok','Snapchat','Instagram','X twitter','WhatsApp','YouTube','Facebook','Telegram','Google Maps','Lugmety','Nana grocery']
for term in TERMS:
    url='https://itunes.apple.com/search?'+urllib.parse.urlencode({'term':term,'country':'sa','entity':'software','limit':4})
    try:
        data=json.loads(urllib.request.urlopen(urllib.request.Request(url,headers={'User-Agent':'Mozilla/5.0'}),timeout=30).read())
        print('==',term,flush=True)
        for z in data.get('results',[]):print('  ',z.get('trackId'),'|',z.get('trackName'),'|',z.get('sellerName'),'|',z.get('bundleId'),'|',(z.get('artworkUrl512') or '')[-40:],flush=True)
    except Exception as e:print('==',term,'ERROR',type(e).__name__,str(e)[:120],flush=True)
    time.sleep(1.5)
# One lookup by id, the way the app will ask.
try:
    z=json.loads(urllib.request.urlopen('https://itunes.apple.com/lookup?id=835599320&country=sa',timeout=30).read());print('lookup',z.get('resultCount'),[(r.get('trackName'),r.get('artworkUrl512')) for r in z.get('results',[])][:1])
except Exception as e:print('lookup ERROR',e)
