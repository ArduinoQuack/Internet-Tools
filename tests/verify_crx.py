"""Check the unchanged upstream CRX3 payload, public-key ID and RSA signature.

Requires Python cryptography. Does not fetch a package or trust an arbitrary key.
"""
from pathlib import Path
import hashlib
import json
import struct
import zipfile
from cryptography.hazmat.primitives.serialization import load_der_public_key
from cryptography.hazmat.primitives.asymmetric import padding
from cryptography.hazmat.primitives import hashes

root = Path(__file__).resolve().parents[1]
expected_sha = 'd688ed3f0262ec4f8bdcd4c8f65987c5444c560aeb387a9e02aca1facdfa7046'
expected_key_sha = '5a6a81097514fb940453d5d46329eca78100e3cc0c5fca508e1a413f77f567bf'
expected_id = 'fkgkibajhfbepljeaefdnfnegdcjomkh'
data = (root / 'upstream/uBlock-1.75.0-original.crx').read_bytes()
assert hashlib.sha256(data).hexdigest() == expected_sha
assert data[:4] == b'Cr24'
version, length = struct.unpack_from('<II', data, 4)
assert version == 3

def fields(buf):
    def vi(pos):
        n = shift = 0
        while True:
            v = buf[pos]; pos += 1; n |= (v & 127) << shift; shift += 7
            if v < 128: return n, pos
    pos = 0
    while pos < len(buf):
        tag, pos = vi(pos)
        assert tag & 7 == 2, 'Unexpected CRX3 protobuf encoding'
        n, pos = vi(pos)
        value = buf[pos:pos+n]; pos += n
        yield tag >> 3, value

header = list(fields(data[12:12+length]))
signed = dict(header)[10000]
crx_id = dict(fields(signed))[1]
ext_id = ''.join(chr(97 + (v >> 4)) + chr(97 + (v & 15)) for v in crx_id)
assert ext_id == expected_id
proofs = [dict(fields(v)) for k,v in header if k == 2]
assert proofs
matched = False
for proof in proofs:
    public, signature = proof[1], proof[2]
    if hashlib.sha256(public).hexdigest() != expected_key_sha: continue
    assert hashlib.sha256(public).digest()[:16] == crx_id
    load_der_public_key(public).verify(signature,
        b'CRX3 SignedData\0' + struct.pack('<I', len(signed)) + signed + data[12+length:],
        padding.PKCS1v15(), hashes.SHA256())
    matched = True
assert matched
with zipfile.ZipFile(root / 'upstream/uBlock-1.75.0-original.crx') as z:
    manifest = json.loads(z.read('manifest.json'))
    assert manifest['version'] == '1.75.0' and manifest['manifest_version'] == 2
    assert 'webRequestBlocking' in manifest['permissions']
    assert any(s.get('run_at') == 'document_start' and s.get('all_frames') for s in manifest['content_scripts'])
print('PASS: upstream uBO 1.75.0 CRX3 hash, pinned RSA proof, extension ID, blocking permission and early frame scripts')

config=json.loads((root/'assets/ubo-package.json').read_text())
compat=(root/'assets/ublock-origin.crx').read_bytes()
assert hashlib.sha256(compat).hexdigest()==config['sha256']
size=struct.unpack_from('<I',compat,8)[0]
header=dict(fields(compat[12:12+size]));signed=header[10000];proof=dict(fields(header[2]));public=proof[1]
assert hashlib.sha256(public).hexdigest()==config['publicKeySha256']
ident=hashlib.sha256(public).digest()[:16]
assert dict(fields(signed))[1]==ident
assert ''.join(chr(97+(v>>4))+chr(97+(v&15)) for v in ident)==config['id']
load_der_public_key(public).verify(proof[2],b'CRX3 SignedData\0'+struct.pack('<I',len(signed))+signed+compat[12+size:],padding.PKCS1v15(),hashes.SHA256())
with zipfile.ZipFile(root/'assets/ublock-origin.crx') as z:
 manifest=json.loads(z.read('manifest.json'));assert manifest['version']==config['version']
 assert 'webRequestBlocking' in manifest['permissions']
 assert 'js/start-compat.js' in z.read('background.html').decode()
 assert 'type="module"' not in z.read('background.html').decode()
 assert b'readManagedBounded' in z.read('js/vapi-background.js')
 assert 'web_accessible_resources/scriptlets.js' in z.namelist() or 'js/resources/scriptlets.js' in z.namelist()
print('PASS: independent compatibility CRX3 signature, ID, startup bundle and blocking permissions')
