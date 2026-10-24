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

# New vision-parser result: actual editable Arabic/mixed-script Word output.
parts = {}
expected = None
for path in Path('app/build/outputs/androidTest-results').rglob('*'):
    if path.is_file() and 'logcat' in path.name:
        logs = path.read_text(errors='replace')
        for value in re.findall(r'MADAR_CLOUD_DOCX[^\n]*?BEGIN (\d+)', logs):
            expected = int(value)
        for index, value in re.findall(r'MADAR_CLOUD_DOCX[^\n]*?PART (\d+) ([A-Za-z0-9+/=]+)', logs):
            parts[int(index)] = value
if expected is None or sorted(parts) != list(range(0, expected, 2000)):
    raise RuntimeError('Synthetic cloud-parser Word document was not collected')
encoded = ''.join(parts[i] for i in sorted(parts))
assert len(encoded) == expected
out = Path('phone-screenshots/cloud-word-editable.docx')
out.write_bytes(base64.b64decode(encoded, validate=True))
with zipfile.ZipFile(out) as archive:
    xml = archive.read('word/document.xml').decode()
    assert '<w:tbl>' in xml and 'سعر المنتج' in xml and '125.00' in xml
print('PASS: real Android vision-parser editable Arabic Word document collected')
