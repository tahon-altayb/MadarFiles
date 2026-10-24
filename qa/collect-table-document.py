"""Collect only a synthetic test document, emitted in bounded logcat records."""
import base64
from pathlib import Path
import re
import zipfile

parts = {}
for path in Path('app/build/outputs/androidTest-results').rglob('*'):
    if path.is_file() and ('logcat' in path.name):
        for index, value in re.findall(r'MADAR_TABLE_DOCX_PART_(\d+):([A-Za-z0-9+/=]+)', path.read_text(errors='replace')):
            parts[int(index)] = value
if not parts or sorted(parts) != list(range(len(parts))):
    raise RuntimeError('Synthetic editable table document was not collected')
out = Path('phone-screenshots/table-editable.docx')
out.parent.mkdir(exist_ok=True)
out.write_bytes(base64.b64decode(''.join(parts[i] for i in sorted(parts)), validate=True))
with zipfile.ZipFile(out) as archive:
    xml = archive.read('word/document.xml').decode()
    assert '<w:tbl>' in xml and 'Product' in xml and '125.00' in xml
print('PASS: real Android Word table document collected')
