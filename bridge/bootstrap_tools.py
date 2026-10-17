#!/usr/bin/env python3
"""Download pinned public build tools and verify their SHA-256 digests."""
from pathlib import Path
import hashlib
import urllib.request

TOOLS = Path(__file__).resolve().parent.parent / 'tools'
FILES = {
    'apktool.jar': ('https://github.com/iBotPeaches/Apktool/releases/download/v2.12.1/apktool_2.12.1.jar',
                    '66cf4524a4a45a7f56567d08b2c9b6ec237bcdd78cee69fd4a59c8a0243aeafa'),
    'ecj.jar': ('https://repo.maven.apache.org/maven2/org/eclipse/jdt/ecj/3.42.0/ecj-3.42.0.jar',
                '29f6d3918ee02db4400c103bc25dd90a22491c3a395867d9393070cb96a7dd29'),
    'android.jar': ('https://repo.maven.apache.org/maven2/com/google/android/android/4.1.1.4/android-4.1.1.4.jar',
                    '84072541cbb711eff89f7277100ff854929a446dba7ceb1b195c340e0b4fd3cb'),
    'r8.jar': ('https://storage.googleapis.com/r8-releases/raw/8.3.37/r8.jar',
               '900dfbc649519969fc5a4c7520d6b7355338e565fa1249874e0190b8d61b1199'),
    'signer.jar': ('https://github.com/patrickfav/uber-apk-signer/releases/download/v1.3.0/uber-apk-signer-1.3.0.jar',
                   'e1299fd6fcf4da527dd53735b56127e8ea922a321128123b9c32d619bba1d835'),
}

if __name__ == '__main__':
    TOOLS.mkdir(exist_ok=True)
    for name, (url, expected) in FILES.items():
        destination = TOOLS / name
        if destination.exists():
            if hashlib.sha256(destination.read_bytes()).hexdigest() != expected:
                raise RuntimeError('Existing tool hash mismatch: ' + name)
            continue
        print('Downloading', name, flush=True)
        temporary = destination.with_suffix('.download')
        with urllib.request.urlopen(url, timeout=90) as source, temporary.open('wb') as output:
            while block := source.read(1024 * 1024): output.write(block)
        if hashlib.sha256(temporary.read_bytes()).hexdigest() != expected:
            temporary.unlink()
            raise RuntimeError('Download hash mismatch: ' + name)
        temporary.rename(destination)
