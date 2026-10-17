"""Generate synthetic, non-personal HKI2 coefficients for CI interoperability tests."""
from pathlib import Path
import struct,math,random,hashlib
from cryptography.hazmat.primitives.ciphers import Cipher,algorithms,modes
from cryptography.hazmat.primitives.padding import PKCS7

rng=random.Random(36013)
directions=[(0,90),(330,90),(30,90),(250,90),(110,90),(0,55),(330,55),(30,55),(250,55),(110,55),(0,110),(330,110),(30,110),(0,110)]
body=bytearray()
for az,polar in directions:
    for ear in range(2):
        body+=struct.pack('<4i',az,polar,2,ear)
        near=(ear==0 and az>180) or (ear==1 and 0<az<180)
        gain=.55 if near else .15 if az else .3
        delay=91 if az==330 and ear==0 else 105 if not near else 87
        for i in range(16384):
            value=gain if i==delay else (rng.random()-.5)*.0002*math.exp(-i/1800) if i>delay else 0
            body+=struct.pack('<f',value)
header=bytearray(144);header[:4]=b'hki2'
struct.pack_into('<I',header,4,0x12345678)
header[8:24]=hashlib.sha256(b'R1 synthetic coefficient fixture').digest()[:16]
struct.pack_into('<2i',header,56,48000,2);header[78]=1
struct.pack_into('<2i',header,88,16384,14)
pad=PKCS7(128).padder();clear=pad.update(body)+pad.finalize()
enc=Cipher(algorithms.AES(hashlib.md5(header[4:8]).digest()),modes.CBC(header[8:24])).encryptor()
hki=header+enc.update(clear)+enc.finalize()
riff=b'RIFF'+struct.pack('<I',len(hki)+12)+b'BRIRh13 '+struct.pack('<I',len(hki))+hki
Path(__file__).with_name('synthetic-test-profile.riff').write_bytes(riff)
print('Generated synthetic HKI2 fixture:',len(riff),'bytes')
