"""Read public landing-page facts without executing page scripts or accepting private hosts."""
import ipaddress,json,re,socket,urllib.parse,urllib.request,urllib.error,hashlib,io
from html.parser import HTMLParser
from pathlib import Path
class Page(HTMLParser):
 def __init__(self):super().__init__();self.skip=0;self.text=[];self.images=[];self.focus=[];self.focus_depth=0;self.article=[];self.article_depth=0;self.structured=[];self.json_depth=0;self.json_text=[]
 def handle_starttag(self,tag,attrs):
  a=dict(attrs)
  if tag in ['main','article']:self.focus_depth+=1
  if tag=='article':self.article_depth+=1
  if tag=='script' and a.get('type','').lower()=='application/ld+json':self.json_depth+=1;self.json_text=[]
  if tag in ['script','style','noscript','svg']:self.skip+=1
  if tag=='meta' and a.get('property') in ['og:image','twitter:image'] and a.get('content'):self.images.append({'url':a['content'],'alt':''})
  if tag=='img':
   url=a.get('data-src') or a.get('data-original') or a.get('src')
   if not url and a.get('srcset'):url=a['srcset'].split(',')[-1].strip().split(' ')[0]
   if url and not url.startswith('data:'):self.images.append({'url':url,'alt':a.get('alt','')[:240]})
 def handle_endtag(self,tag):
  if tag=='script' and self.json_depth:
   self.json_depth=0
   try:self.structured.append(json.loads(''.join(self.json_text)))
   except (ValueError,TypeError):pass
  if tag in ['main','article']:self.focus_depth=max(0,self.focus_depth-1)
  if tag=='article':self.article_depth=max(0,self.article_depth-1)
  if tag in ['script','style','noscript','svg']:self.skip=max(0,self.skip-1)
 def handle_data(self,data):
  if self.json_depth:self.json_text.append(data)
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

SHORT_HOSTS={'share.google','maps.app.goo.gl','goo.gl','g.co','g.page'}
def google_name(url):
 """Business name carried by a Google search/maps/consent URL, or ''. Never fetched content."""
 for _ in range(3):
  try:p=urllib.parse.urlsplit(url)
  except ValueError:return ''
  host=(p.hostname or '').lower();query=urllib.parse.parse_qs(p.query)
  if host.startswith('consent.') and query.get('continue'):url=query['continue'][0];continue
  if re.fullmatch(r'(?:www\.)?google\.[a-z.]+',host):
   place=re.search(r'/maps/place/([^/@]+)',p.path)
   if place:return urllib.parse.unquote_plus(place.group(1)).strip()[:80]
   if p.path.startswith('/search') and query.get('q'):return query['q'][0].strip()[:80]
   if p.path.startswith('/maps') and query.get('q'):return query['q'][0].strip()[:80]
  return ''
 return ''
class NoFollow(urllib.request.HTTPRedirectHandler):
 def redirect_request(self,*args,**kwargs):return None
def short_chain(url):
 """Follow a Google short link's Location headers only, stopping at the first named destination."""
 opener=urllib.request.build_opener(NoFollow());chain=[]
 for _ in range(5):
  url=public_url(url);chain.append(url)
  if google_name(url):break
  try:
   with opener.open(urllib.request.Request(url,method='GET',headers={'User-Agent':'MadarMotion/1.0 (public page preview)'}),timeout=15):break
  except urllib.error.HTTPError as e:
   location=e.headers.get('Location') if e.code in (301,302,303,307,308) else None
   if not location:break
   url=urllib.parse.urljoin(url,location)
  except OSError:break
 return chain
def collect(brief):
 urls=list(dict.fromkeys(re.findall(r'https?://[^\s<>"\u060c]+',brief['text'])))[:2];sources=[];new=[]
 if re.search(r'(?:بدون|تجاهل|اترك)\s+الرابط',brief['text']):urls=[]
 for url in urls:
  url=url.rstrip(').,!?؛')
  # Owned images or a written description can carry the ad when a link is unreadable;
  # without them the user is asked instead of receiving an invented business.
  can_continue=bool(brief.get('images')) or bool(brief.get('contract',{}).get('businessName'))
  name=''
  if (urllib.parse.urlsplit(url).hostname or '').lower() in SHORT_HOSTS:
   try:
    for step in short_chain(url):
     name=google_name(step)
     if name:break
   except (ValueError,OSError):name=''
  if name:
   sources.append({'url':url,'status':'partial','name':name,'text':'اسم النشاط حسب الرابط: '+name+'. الرابط صفحة بحث أو خرائط من Google، ولا يحتوي تفاصيل منتجات أو عروض.','provenance':'link_name_only','images':[]})
   continue
  try:data,resolved,encoding=fetch(url,2_000_000,['text/html','text/plain'])
  except ValueError as error:
   if not can_continue:raise
   sources.append({'url':url,'status':'failed','reason':str(error),'text':'','images':[]});continue
  name=google_name(resolved)
  if name:
   sources.append({'url':resolved,'status':'partial','name':name,'text':'اسم النشاط حسب الرابط: '+name+'.','provenance':'link_name_only','images':[]});continue
  parser=Page()
  try:html=data.decode(encoding,errors='replace')
  except LookupError:html=data.decode('utf-8',errors='replace')
  parser.feed(html);content=parser.content()[:12000]
  if urllib.parse.urlsplit(resolved).hostname in ['accounts.google.com','consent.google.com'] or len(content)<60:
   if not can_continue:raise ValueError('SOURCE_UNAVAILABLE' if len(content)>=60 else 'SOURCE_EMPTY')
   sources.append({'url':resolved,'status':'failed','reason':'SOURCE_EMPTY','text':'','images':[]});continue
  sources.append({'url':resolved,'status':'ok' if len(content)>=200 else 'partial','text':content,'provenance':'public_page_claims_not_independently_verified'})
  image_candidates=list(parser.images)
  def structured_images(value,key=''):
   if isinstance(value,dict):
    for k,v in value.items():
     if k in ['image','photo','logo','thumbnailUrl']:
      if isinstance(v,str):image_candidates.append({'url':v,'alt':k})
      elif isinstance(v,dict) and isinstance(v.get('url') or v.get('contentUrl'),str):image_candidates.append({'url':v.get('contentUrl') or v.get('url'),'alt':k})
     structured_images(v,k)
   elif isinstance(value,list):
    for v in value:
     if key in ['image','photo'] and isinstance(v,str):image_candidates.append({'url':v,'alt':key})
     else:structured_images(v,key)
  for value in parser.structured:structured_images(value)
  source_images=[];visited=set()
  if image_candidates and brief.get('referenceMode')!='vector':
   from PIL import Image,ImageOps
   for candidate in sorted(image_candidates,key=lambda z:bool(re.search(r'product|brand|logo|food|menu|منتج|شعار|فطير|شاهي',z.get('alt',''),re.I)),reverse=True)[:32]:
    if len(brief.get('images',[]))+len(new)>=8:break
    address=urllib.parse.urljoin(resolved,candidate['url'])
    if address in visited:continue
    visited.add(address)
    if re.search(r'(?:googlelogo|gstatic\.com/images/branding|favicon|loading\.|spinner|pixel\.|/icons?/)',address,re.I):continue
    try:
     image_data,image_url,_=fetch(address,8_000_000,['image/'])
     image=Image.open(io.BytesIO(image_data))
     if image.width<96 or image.height<96 or image.width*image.height>24_000_000:continue
     image.load();image=ImageOps.exif_transpose(image).convert('RGB');image.thumbnail((1800,1800))
     name='asset_'+hashlib.sha256(image_data).hexdigest()[:32]+'.jpg'
     if name in new or name in brief.get('images',[]):continue
     image.save(name,quality=92);new.append(name);source_images.append({'asset':name,'url':image_url,'alt':candidate.get('alt','')})
    except (OSError,ValueError,Image.DecompressionBombError):continue
  sources[-1]['images']=source_images
  if image_candidates and not source_images:sources[-1]['imageUnavailable']=True
 brief['sources']=sources;brief['images']=list(dict.fromkeys(brief.get('images',[])+new));return new
