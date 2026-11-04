"""Real public-page fetch plus private-host rejection; no cloud/model stubs."""
import importlib.util,os
from pathlib import Path
old=Path.cwd();source=old/'app/src/main/assets/motion/sources.py'
spec=importlib.util.spec_from_file_location('motion_sources',source);module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
for url in ['file:///etc/passwd','http://127.0.0.1/','http://[::1]/','https://user:password@example.com/','https://example.com:8443/']:
 try:module.public_url(url);raise AssertionError('Private or invalid URL accepted')
 except ValueError:pass
brief={'text':'اقرأ هذه الصفحة كمصدر للمعلومات: https://example.com/','images':[]}
assets=module.collect(brief)
assert assets==[] and len(brief['sources'])==1
assert brief['sources'][0]['url'].startswith('https://example.com')
assert len(brief['sources'][0]['text'])>60
assert brief['sources'][0]['provenance']=='public_page_claims_not_independently_verified'
print('PASS: actual HTTPS page facts, provenance, no fabricated imagery, private/credential/port URL rejection')
