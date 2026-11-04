"""Read public landing-page facts without executing page scripts or accepting private hosts."""
import ipaddress,json,re,socket,urllib.parse,urllib.request,hashlib,io
from html.parser import HTMLParser
from pathlib import Path
class Page(HTMLParser):
 def __init__(self):super().__init__();self.skip=0;self.text=[];self.images=[];self.focus=[];self.focus_depth=0;self.article=[];self.article_depth=0
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag in ['main','article']:self.focus_depth+=1
  if tag=='article':self.article_depth+=1
  if tag in ['script','style','noscript','svg']:self.skip+=1
  if tag=='meta' and a.get('property') in ['og:image','twitter:image'] and a.get('content'):self.images.append(a['content'])
 def handle_endtag(self,tag):
  if tag in ['main','article']:self.focus_depth=max(0,self.focus_depth-1)
  if tag=='article':self.article_depth=max(0,self.article_depth-1)
  if tag in ['script','style','noscript','svg']:self.skip=max(0,self.skip-1)
 def handle_data(self,data):
  if not self.skip and data.strip():
   self.text.append(data.strip())
   if self.focus_depth:self.focus.append(data.strip())
   if self.article_depth:self.article.append(data.strip())
 def content(self):
  article='\n'.join(self.article)
  if len(article)>=60:return article
  focus='\n'.join(self.focus)
  return focus if len(focus)>=60 else '\n'.join(self.text)
def normalized_url(url):
 try:
  p=urllib.parse.urlsplit(url);port=p.port
  if p.scheme not in ['https','http'] or not p.hostname or p.username or p.password or port not in [None,80,443]:raise ValueError('SOURCE_URL')
  host=p.hostname.encode('idna').decode('ascii');netloc=('['+host+']') if ':' in host else host
  if port is not None:netloc+=':'+str(port)
  safe="/%:@!$&'()*+,;=-._~"
  return urllib.parse.urlunsplit((p.scheme,netloc,urllib.parse.quote(p.path,safe=safe),urllib.parse.quote(p.query,safe=safe+'?'),urllib.parse.quote(p.fragment,safe=safe+'?')))
 except (ValueError,UnicodeError) as e:raise ValueError('SOURCE_URL') from e
def public_url(url):
 url=normalized_url(url);p=urllib.parse.urlsplit(url)
 records=socket.getaddrinfo(p.hostname,p.port or (443 if p.scheme=='https' else 80),type=socket.SOCK_STREAM)
 if not records or any(not ipaddress.ip_address(r[4][0].split('%')[0]).is_global for r in records):raise ValueError('SOURCE_HOST')
 return url

class Redirect(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,req,fp,code,msg,headers,newurl):newurl=public_url(newurl);return super().redirect_request(req,fp,code,msg,headers,newurl)
def fetch(url,limit,mimes):
 try:url=public_url(url)
 except OSError as e:raise ValueError('SOURCE_UNAVAILABLE') from e
 opener=urllib.request.build_opener(Redirect())
 try:
  with opener.open(urllib.request.Request(url,headers={'User-Agent':'MadarMotion/1.0 (public page preview)'}),timeout=25) as response:
   mime=response.headers.get_content_type()
   if not any(mime.startswith(t) for t in mimes):raise ValueError('SOURCE_TYPE')
   data=response.read(limit+1)
   if len(data)>limit:raise ValueError('SOURCE_SIZE')
   return data,response.geturl(),response.headers.get_content_charset() or 'utf-8'
 except OSError as e:raise ValueError('SOURCE_UNAVAILABLE') from e

def collect(brief):
 urls=list(dict.fromkeys(re.findall(r'https?://[^\s<>"\u060c]+',brief['text'])))[:2];sources=[];new=[]
 if re.search(r'(?:بدون|تجاهل|اترك)\s+الرابط',brief['text']):urls=[]
 for url in urls:
  data,resolved,encoding=fetch(url.rstrip(').,!?؛'),2_000_000,['text/html','text/plain']);parser=Page()
  try:html=data.decode(encoding,errors='replace')
  except LookupError:html=data.decode('utf-8',errors='replace')
  parser.feed(html);content=parser.content()[:12000]
  if len(content)<60:raise ValueError('SOURCE_EMPTY')
  sources.append({'url':resolved,'text':content,'provenance':'public_page_claims_not_independently_verified'})
  if len(brief.get('images',[]))+len(new)<8 and parser.images:
   # A landing page may provide a product photo. Keep it separate from verified user images.
   from PIL import Image,ImageOps
   try:
    image_data,_,_=fetch(urllib.parse.urljoin(resolved,parser.images[0]),8_000_000,['image/'])
    image=Image.open(io.BytesIO(image_data));image.load();image=ImageOps.exif_transpose(image).convert('RGB');image.thumbnail((1800,1800))
    name='asset_'+hashlib.sha256(image_data).hexdigest()[:32]+'.jpg';image.save(name,quality=92);new.append(name)
   except (OSError,ValueError,Image.DecompressionBombError):
    sources[-1]['imageUnavailable']=True  # Valid page facts can still drive typography and vectors.
 brief['sources']=sources;brief['images']=list(dict.fromkeys(brief.get('images',[])+new));return new
