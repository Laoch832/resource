#!/usr/bin/env python3
"""Build internal R1 candidates and a private Android runtime probe. No completion claim."""
from pathlib import Path
from xml.etree import ElementTree as ET
import shutil,subprocess,sys,zipfile,tempfile,re,hashlib,os

ROOT=Path(__file__).resolve().parent.parent
R1=ROOT/'r1'; BUILD=R1/'build'; TOOLS=ROOT/'tools'
A='{http://schemas.android.com/apk/res/android}'
ET.register_namespace('android','http://schemas.android.com/apk/res/android')

def run(args,log):
    print(log,flush=True)
    with (BUILD/log).open('w') as out:subprocess.run([str(a) for a in args],check=True,stdout=out,stderr=subprocess.STDOUT,cwd=ROOT)

def compile_helpers(probe=False):
    classes=Path(tempfile.mkdtemp(prefix='classes-',dir=BUILD))
    sources=sorted((R1/'src').rglob('*.java'))+sorted((ROOT/'bridge/src').rglob('*.java'))
    if probe:sources += [R1/'tests/AndroidProbeActivity.java',R1/'tests/RendererTests.java']
    run(['java','-jar',TOOLS/'ecj.jar','-8','-encoding','UTF-8','-cp',R1/'android-api30.jar','-d',classes,*sources],'compile.log')
    jar=classes/'helpers.jar'
    with zipfile.ZipFile(jar,'w') as z:
        for p in classes.rglob('*.class'):z.write(p,str(p.relative_to(classes)))
    dex=Path(tempfile.mkdtemp(prefix='dex-',dir=BUILD))
    jre=Path(shutil.which('java')).resolve().parent.parent
    run(['java','-cp',TOOLS/'r8.jar','com.android.tools.r8.D8','--min-api','23','--lib',R1/'android-api30.jar','--lib',jre,'--output',dex,jar],'d8.log')
    return dex/'classes.dex'

def sign(apk):
    password=os.environ['R1_KEYSTORE_PASSWORD']
    run(['java','-jar',TOOLS/'signer.jar','-a',apk,'--ks',ROOT/'bridge/signing/research.jks','--ksAlias','bridge','--ksPass',password,'--ksKeyPass',password,'--allowResign','--overwrite'],apk.stem+'-sign.log')
    verifier=ROOT/'bridge/build/signature-check'
    run(['java','-cp',str(verifier)+':'+str(TOOLS/'signer.jar'),'VerifySignature',apk],apk.stem+'-verify-signature.log')

def add(parent,tag,**attrs):return ET.SubElement(parent,tag,{A+k:v for k,v in attrs.items()})

def prepare_inputs():
    originals=[('sony','sound-connect-13.3.0-original.apk','e6c5ac8085d5cb785a9391b53ef96944c3aac9dcd20512794a4a4847e819e1a2'),('netease','netease-9.6.08-original.apk','4c2e8791c6d00b952332eb698837bbea72bb4384518b75fe895bd4fdccb572d3')]
    for kind,name,expected in originals:
        if hashlib.sha256((ROOT/name).read_bytes()).hexdigest()!=expected:raise RuntimeError('Wrong upstream APK: '+name)
        target=ROOT/'patch'/kind
        if not (target/'AndroidManifest.xml').is_file():run(['java','-jar',TOOLS/'apktool.jar','d','-s','-f','-o',target,ROOT/name],kind+'-decode.log')
    sony_source=ROOT/'patch/sony-dex3'
    if not (sony_source/'smali').is_dir():
        mini=BUILD/'sony-dex3.apk'
        with zipfile.ZipFile(ROOT/'sound-connect-13.3.0-original.apk') as src,zipfile.ZipFile(mini,'w') as dst:
            dst.writestr('classes.dex',src.read('classes3.dex'));dst.writestr('AndroidManifest.xml',src.read('AndroidManifest.xml'))
        run(['java','-jar',TOOLS/'apktool.jar','d','-r','-f','-o',sony_source,mini],'sony-dex-decode.log')
    sys.path.insert(0,str(ROOT/'bridge'))
    import prepare_patch
    sony_hook=ROOT/'patch/sony/smali_classes3/com/sony/songpal/earcapture/j2objc/immersiveaudio/IaController.smali'
    if not sony_hook.is_file() or 'Ldev/soundconnect/bridge/SourceBridge;' not in sony_hook.read_text():prepare_patch.patch_sony_dex()
    for kind,component in [('sony','dev.soundconnect.bridge.HrtfProvider'),('netease','dev.soundconnect.bridge.BridgeActivity')]:
        app=ET.parse(ROOT/'patch'/kind/'AndroidManifest.xml').getroot().find('application')
        if not any(n.get(A+'name')==component for n in app):prepare_patch.patch_manifest(kind)

def patch_netease():
    if not (R1/'netease-dex16/smali').is_dir():
        mini=BUILD/'netease-dex16.apk'
        with zipfile.ZipFile(ROOT/'netease-9.6.08-original.apk') as src,zipfile.ZipFile(mini,'w') as dst:
            dst.writestr('classes.dex',src.read('classes16.dex'));dst.writestr('AndroidManifest.xml',src.read('AndroidManifest.xml'))
        run(['java','-jar',TOOLS/'apktool.jar','d','-r','-f','-o',R1/'netease-dex16',mini],'netease-dex-decode.log')
    smali=R1/'netease-dex16/smali/com/netease/cloudmusic/media'
    builder=smali/'audioplayer/NCMNativeAudioTrackBuilder.smali'
    s=builder.read_text()
    if 'Ldev/soundconnect/r1/SoundAudioTrack;' not in s:
        signature='.method public static create(Landroid/media/AudioAttributes;IIIIIII)Landroid/media/AudioTrack;'
        replacement=signature+'''\n    .locals 1
    invoke-static/range {p0 .. p7}, Ldev/soundconnect/r1/SoundAudioTrack;->create(Landroid/media/AudioAttributes;IIIIIII)Landroid/media/AudioTrack;
    move-result-object v0
    return-object v0
.end method'''
        s,n=re.subn(re.escape(signature)+r'.*?\.end method',lambda m:replacement,s,flags=re.S)
        if n!=1:raise RuntimeError('Builder patch count')
        builder.write_text(s)
    for relative,expected in [('NEAudioDevice.smali',1),('player/nmAudioTrack.smali',5)]:
        p=smali/relative;s=p.read_text()
        if 'Ldev/soundconnect/r1/SoundAudioTrack;' in s:continue
        s,n=re.subn(r'(new-instance [^,]+, )Landroid/media/AudioTrack;',r'\1Ldev/soundconnect/r1/SoundAudioTrack;',s)
        if n!=expected:raise RuntimeError('Constructor allocation count '+relative)
        if s.count('Landroid/media/AudioTrack;-><init>')!=expected:raise RuntimeError('Constructor call count '+relative)
        s=s.replace('Landroid/media/AudioTrack;-><init>','Ldev/soundconnect/r1/SoundAudioTrack;-><init>');p.write_text(s)
    run(['java','-jar',TOOLS/'apktool.jar','b',R1/'netease-dex16','-o',BUILD/'netease-pcm-dex.apk'],'pcm-dex-build.log')
    with zipfile.ZipFile(BUILD/'netease-pcm-dex.apk') as z:(ROOT/'patch/netease/classes16.dex').write_bytes(z.read('classes.dex'))
    path=ROOT/'patch/netease/AndroidManifest.xml';tree=ET.parse(path);app=tree.getroot().find('application')
    app.set(A+'extractNativeLibs','true');app.set(A+'label','网易云音乐 · Sony R1 验证')
    names={n.get(A+'name') for n in app}
    if 'dev.soundconnect.r1.InitProvider' not in names:
        add(app,'provider',name='dev.soundconnect.r1.InitProvider',authorities='com.netease.cloudmusic.r1.init',exported='false')
        add(app,'provider',name='dev.soundconnect.r1.InitProvider$Playback',authorities='com.netease.cloudmusic.r1.init.play',exported='false',process=':play')
        add(app,'activity',name='dev.soundconnect.r1.MpeghPlayerActivity',exported='false',label='本机 MPEG-H / 360RA 播放',theme='@android:style/Theme.Material.Light')
    for n in app:
        if n.get(A+'name') in ['dev.soundconnect.bridge.BridgeActivity','dev.soundconnect.bridge.BridgeLauncher']:n.set(A+'label','Sony 耳形音频处理 · R1 验证')
    tree.write(path,encoding='utf-8',xml_declaration=True)
    for abi,build in [('arm64-v8a','libmpegh-android-arm64'),('armeabi-v7a','libmpegh-android-armv7')]:
        dest=ROOT/'patch/netease/lib'/abi/'libr1mpegh_exec.so';dest.parent.mkdir(parents=True,exist_ok=True)
        shutil.copy2(R1/build/'ia_mpeghd_testbench',dest)
    licenses=ROOT/'patch/netease/assets/r1-licenses';licenses.mkdir(parents=True,exist_ok=True)
    for name in ['LICENSE','LICENSE2']:shutil.copy2(R1/'libmpegh'/name,licenses/('libmpegh-'+name+'.txt'))

def probe(dex):
    manifest=BUILD/'probe-manifest.xml'
    manifest.write_text('''<manifest xmlns:android="http://schemas.android.com/apk/res/android" package="dev.soundconnect.r1.probe" android:versionCode="1" android:versionName="1"><uses-sdk android:minSdkVersion="23" android:targetSdkVersion="30"/><queries><package android:name="com.netease.cloudmusic"/></queries><application android:label="R1 local runtime probe" android:debuggable="true" android:extractNativeLibs="true"><activity android:name="dev.soundconnect.r1.AndroidProbeActivity" android:exported="true"/></application></manifest>''')
    apk=BUILD/'r1-local-probe.apk'
    run([TOOLS/'aapt2','link','--manifest',manifest,'-I',R1/'android-api30.jar','-o',apk],'probe-aapt.log')
    with zipfile.ZipFile(apk,'a',compression=zipfile.ZIP_DEFLATED) as z:
        z.write(dex,'classes.dex')
        z.write(R1/'libmpegh-android-x86_64/ia_mpeghd_testbench','lib/x86_64/libr1mpegh_exec.so')
        z.write(R1/'synthetic-test-profile.riff','assets/test-profile.riff')
        z.write(R1/'libmpegh/smoke_test_suite/inp/sine_1khz_cicp6.mhas','assets/test.mhas')
    sign(apk)

def refresh(dex):
    for kind,index,name in [('sony',7,'sound-connect-13.3.0-r1-candidate'),('netease',21,'netease-9.6.08-r1-candidate')]:
        apk=BUILD/'candidates'/(name+'.apk')
        replacements={f'classes{index}.dex':dex}
        if kind=='netease':
            for abi,build in [('arm64-v8a','libmpegh-android-arm64'),('armeabi-v7a','libmpegh-android-armv7')]:
                replacements[f'lib/{abi}/libr1mpegh_exec.so']=R1/build/'ia_mpeghd_testbench'
        temporary=apk.with_suffix('.refreshed.apk')
        with zipfile.ZipFile(apk) as src,zipfile.ZipFile(temporary,'w') as dst:
            for info in src.infolist():
                if info.filename.startswith('META-INF/'):continue
                data=replacements.pop(info.filename,None)
                dst.writestr(info,data.read_bytes() if data else src.read(info.filename))
            if replacements:raise RuntimeError('Expected replacement entries missing '+str(list(replacements)))
        temporary.replace(apk);sign(apk)

def main():
    BUILD.mkdir(exist_ok=True)
    run(['java','-jar',TOOLS/'ecj.jar','-8','-cp',TOOLS/'signer.jar','-d',ROOT/'bridge/build/signature-check',ROOT/'bridge/VerifySignature.java'],'compile-verifier.log')
    is_probe='--probe' in sys.argv
    dex=compile_helpers(is_probe)
    if is_probe:probe(dex);return
    if '--refresh' in sys.argv:refresh(dex);return
    prepare_inputs()
    patch_netease()
    candidates=BUILD/'candidates';candidates.mkdir(exist_ok=True)
    for kind,index,name in [('sony',7,'sound-connect-13.3.0-r1-candidate'),('netease',21,'netease-9.6.08-r1-candidate')]:
        shutil.copy2(dex,ROOT/'patch'/kind/f'classes{index}.dex')
        if kind=='sony':
            path=ROOT/'patch/sony/AndroidManifest.xml';tree=ET.parse(path);tree.getroot().find('application').set(A+'label','Sound Connect · Sony R1 验证');tree.write(path,encoding='utf-8',xml_declaration=True)
        apk=candidates/(name+'.apk')
        run(['java','-jar',TOOLS/'apktool.jar','b',ROOT/'patch'/kind,'-o',apk],kind+'-build.log');sign(apk)
    print('Internal candidates built; inspect runtime evidence before release.',flush=True)

if __name__=='__main__':main()
