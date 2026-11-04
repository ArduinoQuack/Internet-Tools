"""Sign this independent compatibility build with its own CRX3 key and ID."""
from pathlib import Path
import base64,hashlib,json,struct,sys,zipfile,io
from cryptography.hazmat.primitives import hashes,serialization
from cryptography.hazmat.primitives.asymmetric import rsa,padding
root=Path(__file__).resolve().parents[1];work=Path(sys.argv[1]).resolve();keyfile=root/'private/ubo-compat.pem'
keyfile.parent.mkdir(parents=True,exist_ok=True)
if keyfile.exists():key=serialization.load_pem_private_key(keyfile.read_bytes(),password=None)
else:
 key=rsa.generate_private_key(public_exponent=65537,key_size=2048)
 keyfile.write_bytes(key.private_bytes(serialization.Encoding.PEM,serialization.PrivateFormat.PKCS8,serialization.NoEncryption()));keyfile.chmod(0o600)
pub=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo);digest=hashlib.sha256(pub).digest();extid=''.join(chr(97+(x>>4))+chr(97+(x&15)) for x in digest[:16])
manifest=json.loads((work/'manifest.json').read_text());manifest.update(key=base64.b64encode(pub).decode(),name='uBlock Origin',description='Independent Samsung compatibility build based on uBlock Origin 1.75.0. Not an official upstream release.',version='1.75.0.7',version_name='1.75.0')
manifest.pop('update_url',None);(work/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n')
# package.json is Node-only test metadata, never part of the extension.
payload=io.BytesIO()
with zipfile.ZipFile(payload,'w',zipfile.ZIP_DEFLATED,compresslevel=9) as z:
 for p in sorted(work.rglob('*')):
  if p.is_file() and p.name!='package.json':
   name=p.relative_to(work).as_posix();info=zipfile.ZipInfo(name,(1980,1,1,0,0,0));info.compress_type=zipfile.ZIP_DEFLATED;info.external_attr=0o644<<16;z.writestr(info,p.read_bytes())
def vi(n):
 b=bytearray()
 while n>127:b.append((n&127)|128);n>>=7
 b.append(n);return bytes(b)
def field(n,value):return vi((n<<3)|2)+vi(len(value))+value
signed=field(1,digest[:16]);data=payload.getvalue();signature=key.sign(b'CRX3 SignedData\0'+struct.pack('<I',len(signed))+signed+data,padding.PKCS1v15(),hashes.SHA256())
header=field(2,field(1,pub)+field(2,signature))+field(10000,signed);crx=b'Cr24'+struct.pack('<II',3,len(header))+header+data
(root/'assets/ublock-origin.crx').write_bytes(crx)
config={'id':extid,'version':'1.75.0.7','upstreamVersion':'1.75.0','publicKeySha256':hashlib.sha256(pub).hexdigest(),'sha256':hashlib.sha256(crx).hexdigest(),'compatBuild':7}
(root/'assets/ubo-background-probe.js').write_text((root/'compat/probe.js.template').read_text().replace('@EXTENSION_ID@',extid))
(root/'assets/ubo-package.json').write_text(json.dumps(config,indent=2)+'\n')
(root/'src/dev/dov/internettools/ExtensionConfig.java').write_text('package dev.dov.internettools;\nfinal class ExtensionConfig {\n static final String ID="'+extid+'";\n static final String VERSION="1.75.0.7";\n static final String SHA256="'+config['sha256']+'";\n static final String LEGACY_ID="fkgkibajhfbepljeaefdnfnegdcjomkh";\n}\n')
print(json.dumps(config))
