"""Read public landing-page facts without executing page scripts or accepting private hosts."""
import ipaddress,json,re,socket,urllib.parse,urllib.request,hashlib,io
from html.parser import HTMLParser
from pathlib import Path
class Page(HTMLParser):
 def __init__(self):super().__init__();self.skip=0;self.text=[];self.images=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag in ['script','style','noscript','svg']:self.skip+=1
  if tag=='meta' and a.get('property') in ['og:image','twitter:image'] and a.get('content'):self.images.append(a['content'])
 def handle_endtag(self,tag):
  if tag in ['script','style','noscript','svg']:self.skip=max(0,self.skip-1)
 def handle_data(self,data):
  if not self.skip and data.strip():self.text.append(data.strip())
def public_url(url):
 p=urllib.parse.urlsplit(url)
 if p.scheme not in ['https','http'] or not p.hostname or p.username or p.password or p.port not in [None,80,443]:raise ValueError('SOURCE_URL')
 records=socket.getaddrinfo(p.hostname,p.port or (443 if p.scheme=='https' else 80),type=socket.SOCK_STREAM)
 if not records or any(not ipaddress.ip_address(r[4][0].split('%')[0]).is_global for r in records):raise ValueError('SOURCE_HOST')
 return url
class Redirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):public_url(newurl);return super().redirect_request(req,fp,code,msg,headers,newurl)
def fetch(url,limit,mimes):
 public_url(url);opener=urllib.request.build_opener(Redirect())
 with opener.open(urllib.request.Request(url,headers={'User-Agent':'MadarMotion/1.0 (public page preview)'}),timeout=25) as response:
  mime=response.headers.get_content_type()
  if not any(mime.startswith(t) for t in mimes):raise ValueError('SOURCE_TYPE')
  data=response.read(limit+1)
  if len(data)>limit:raise ValueError('SOURCE_SIZE')
  return data,response.geturl(),response.headers.get_content_charset() or 'utf-8'
def collect(brief):
 urls=list(dict.fromkeys(re.findall(r'https?://[^\s<>"\u060c]+',brief['text'])))[:2];sources=[];new=[]
 for url in urls:
  data,resolved,encoding=fetch(url.rstrip(').,!?؛'),2_000_000,['text/html','text/plain']);parser=Page();parser.feed(data.decode(encoding,errors='replace'))
  content='\n'.join(parser.text)[:12000]
  if len(content)<60:raise ValueError('SOURCE_EMPTY')
  sources.append({'url':resolved,'text':content,'provenance':'public_page_claims_not_independently_verified'})
  if len(brief.get('images',[]))+len(new)<8 and parser.images:
   # A landing page may provide a product photo. Keep it separate from verified user images.
   from PIL import Image,ImageOps
   image_data,_,_=fetch(urllib.parse.urljoin(resolved,parser.images[0]),8_000_000,['image/'])
   image=Image.open(io.BytesIO(image_data));image.load();image=ImageOps.exif_transpose(image).convert('RGB');image.thumbnail((1800,1800))
   name='asset_'+hashlib.sha256(image_data).hexdigest()[:32]+'.jpg';image.save(name,quality=92);new.append(name)
 brief['sources']=sources;brief['images']=list(dict.fromkeys(brief.get('images',[])+new));return new
