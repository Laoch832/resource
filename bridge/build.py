#!/usr/bin/env python3
"""Rebuild experimental profile-transport APKs from checked upstream APKs.

This does not implement or verify 360RA audio rendering. Signing keys stay local.
"""
from pathlib import Path
import hashlib
import os
import shutil
import subprocess
import sys
import tempfile
import zipfile

ROOT = Path(__file__).resolve().parent.parent
TOOLS = ROOT / 'tools'
BRIDGE = ROOT / 'bridge'
BUILD = BRIDGE / 'build'
OUT = ROOT / 'deliverables'
JAVA_HOME = Path(shutil.which('java')).resolve().parent.parent
ORIGINALS = {
    'sony': ('sound-connect-13.3.0-original.apk', 'e6c5ac8085d5cb785a9391b53ef96944c3aac9dcd20512794a4a4847e819e1a2'),
    'netease': ('netease-9.6.08-original.apk', '4c2e8791c6d00b952332eb698837bbea72bb4384518b75fe895bd4fdccb572d3'),
}


def run(command, log):
    print('Running:', log, flush=True)
    with (BUILD / log).open('w') as stream:
        subprocess.run([str(x) for x in command], check=True, stdout=stream, stderr=subprocess.STDOUT)


def main():
    BUILD.mkdir(parents=True, exist_ok=True)
    OUT.mkdir(exist_ok=True)
    for filename, expected in ORIGINALS.values():
        if hashlib.sha256((ROOT / filename).read_bytes()).hexdigest() != expected:
            raise RuntimeError('Upstream APK hash mismatch: ' + filename)
    for name in ('apktool.jar', 'ecj.jar', 'android.jar', 'r8.jar', 'signer.jar'):
        if not (TOOLS / name).is_file(): raise RuntimeError('Missing tool: ' + name + '; see README')

    sources = sorted((BRIDGE / 'src').rglob('*.java'))
    android_classes = Path(tempfile.mkdtemp(prefix='android-', dir=BUILD))
    test_classes = Path(tempfile.mkdtemp(prefix='tests-', dir=BUILD))
    run(['java', '-jar', TOOLS / 'ecj.jar', '-source', '8', '-target', '8', '-encoding', 'UTF-8',
         '-cp', TOOLS / 'android.jar', '-d', android_classes, *sources], 'compile.log')
    test_sources = [BRIDGE / 'src/dev/soundconnect/bridge/SourceBridge.java',
                    BRIDGE / 'src/dev/soundconnect/bridge/ProfileInfo.java', *sorted((BRIDGE / 'tests').glob('*.java'))]
    run(['java', '-jar', TOOLS / 'ecj.jar', '-source', '8', '-target', '8', '-encoding', 'UTF-8',
         '-d', test_classes, *test_sources], 'compile-tests.log')
    run(['java', '-cp', test_classes, 'BridgeTests'], 'tests.log')
    classes = BUILD / 'bridge-classes.jar'
    with zipfile.ZipFile(classes, 'w') as archive:
        for path in sorted(android_classes.rglob('*.class')):
            archive.write(path, str(path.relative_to(android_classes)))
    (BUILD / 'dex').mkdir(exist_ok=True)
    run(['java', '-cp', TOOLS / 'r8.jar', 'com.android.tools.r8.D8', '--min-api', '23',
         '--lib', TOOLS / 'android.jar', '--lib', JAVA_HOME, '--output', BUILD / 'dex', classes], 'd8.log')

    for kind, (filename, _) in ORIGINALS.items():
        if not (ROOT / 'patch' / kind / 'AndroidManifest.xml').exists():
            run(['java', '-jar', TOOLS / 'apktool.jar', 'd', '-s', '-f', '-o', ROOT / 'patch' / kind,
                 ROOT / filename], kind + '-decode.log')
    if not (ROOT / 'patch/sony/smali_classes3').exists():
        mini = BUILD / 'sony-dex3.apk'
        with zipfile.ZipFile(ROOT / ORIGINALS['sony'][0]) as upstream, zipfile.ZipFile(mini, 'w') as archive:
            archive.writestr('classes.dex', upstream.read('classes3.dex'))
            archive.writestr('AndroidManifest.xml', upstream.read('AndroidManifest.xml'))
        run(['java', '-jar', TOOLS / 'apktool.jar', 'd', '-r', '-f', '-o', ROOT / 'patch/sony-dex3', mini],
            'sony-dex-decode.log')
        run([sys.executable, BRIDGE / 'prepare_patch.py'], 'prepare.log')
    for kind, index in [('sony', 7), ('netease', 21)]:
        shutil.copy2(BUILD / 'dex/classes.dex', ROOT / 'patch' / kind / f'classes{index}.dex')
        name = ('sound-connect-13.3.0' if kind == 'sony' else 'netease-9.6.08') + '-bridge-experimental'
        run(['java', '-jar', TOOLS / 'apktool.jar', 'b', ROOT / 'patch' / kind, '-o',
             OUT / (name + '-unsigned.apk')], kind + '-build.log')

    signing = BRIDGE / 'signing'
    signing.mkdir(mode=0o700, exist_ok=True)
    keystore = signing / 'research.jks'
    password = os.environ['R1_KEYSTORE_PASSWORD']
    if not keystore.exists():
        run(['keytool', '-genkeypair', '-keystore', keystore, '-storepass', password, '-keypass', password,
             '-alias', 'bridge', '-keyalg', 'RSA', '-keysize', '3072', '-validity', '3650',
             '-dname', 'CN=SoundConnect NetEase Experimental Bridge', '-noprompt'], 'keytool.log')
        keystore.chmod(0o600)
    run(['java', '-jar', TOOLS / 'signer.jar', '-a', *sorted(OUT.glob('*-unsigned.apk')),
         '--ks', keystore, '--ksAlias', 'bridge', '--ksPass', password, '--ksKeyPass', password,
         '--allowResign', '--overwrite'], 'signing.log')
    finals = []
    for unsigned in sorted(OUT.glob('*-unsigned.apk')):
        final = unsigned.with_name(unsigned.name.replace('-unsigned', ''))
        shutil.copy2(unsigned, final)
        finals.append(final)
    run(['java', '-jar', TOOLS / 'ecj.jar', '-source', '8', '-target', '8', '-cp', TOOLS / 'signer.jar',
         '-d', BUILD / 'signature-check', BRIDGE / 'VerifySignature.java'], 'compile-signature-check.log')
    run(['java', '-cp', str(BUILD / 'signature-check') + ':' + str(TOOLS / 'signer.jar'),
         'VerifySignature', *finals], 'signature-verification.log')
    print('Build finished. This is a profile-transport prototype; real 360RA rendering remains unimplemented.')


if __name__ == '__main__':
    main()
