#!/usr/bin/env python3
"""Apply small, checked changes to apktool-decoded copies of the two originals."""
from pathlib import Path
from xml.etree import ElementTree as ET
import shutil

ROOT = Path(__file__).resolve().parent.parent
A = '{http://schemas.android.com/apk/res/android}'
PERMISSION = 'dev.soundconnect.bridge.permission.READ_HRTF'
AUTHORITY = 'com.sony.songpal.mdr.netease360ra'
ET.register_namespace('android', 'http://schemas.android.com/apk/res/android')


def require_once(text, old, new):
    if text.count(old) != 1:
        raise RuntimeError(f'Expected exactly one patch location, found {text.count(old)}: {old[:80]}')
    return text.replace(old, new)


def patch_sony_dex():
    source = ROOT / 'patch/sony-dex3/smali'
    destination = ROOT / 'patch/sony/smali_classes3'
    shutil.copytree(source, destination, dirs_exist_ok=True)
    path = destination / 'com/sony/songpal/earcapture/j2objc/immersiveaudio/IaController.smali'
    text = path.read_text()
    old = '''    :try_start_1
    iget-object v1, p0, Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/IaController;->h:Ljava/util/List;

    .line 40'''
    new = '''    :try_start_1
    iget-object v1, p0, Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/IaController;->h:Ljava/util/List;

    invoke-static {v1}, Ldev/soundconnect/bridge/SourceBridge;->addProvider(Ljava/util/List;)V

    .line 40'''
    text = require_once(text, old, new)
    old = '''.method public static synthetic b(Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/IaController;Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/IaController$k;Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/OS;Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/IaDeviceModel;Lcom/sony/songpal/earcapture/j2objc/immersiveaudio/ServiceProviderApp;Ljava/lang/String;)V
    .locals 8
'''
    new = old + '''
    invoke-static {p4, p1, p3, p5}, Ldev/soundconnect/bridge/SourceBridge;->route(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;)Z
    move-result v0
    if-eqz v0, :bridge_original
    return-void
    :bridge_original
'''
    text = require_once(text, old, new)
    path.write_text(text)
    (ROOT / 'patch/sony/classes3.dex').unlink(missing_ok=True)


def xml_add(parent, tag, **attributes):
    return ET.SubElement(parent, tag, {A + k: v for k, v in attributes.items()})


def patch_manifest(kind):
    path = ROOT / 'patch' / kind / 'AndroidManifest.xml'
    tree = ET.parse(str(path))
    root = tree.getroot()
    application = root.find('application')
    if any(node.get(A + 'name') == 'dev.soundconnect.bridge.HrtfProvider' for node in application):
        raise RuntimeError('Manifest already patched: ' + str(path))
    if kind == 'sony':
        xml_add(root, 'permission', name=PERMISSION, protectionLevel='signature')
        application.set(A + 'label', 'Sound Connect · 参数桥实验')
        xml_add(application, 'provider', name='dev.soundconnect.bridge.HrtfProvider',
                authorities=AUTHORITY, exported='true', readPermission=PERMISSION,
                writePermission=PERMISSION, grantUriPermissions='false')
    else:
        if any(node.get(A + 'name') == 'dev.soundconnect.bridge.BridgeActivity' for node in application):
            raise RuntimeError('Manifest already patched: ' + str(path))
        xml_add(root, 'uses-permission', name=PERMISSION)
        queries = root.find('queries')
        if queries is None: queries = ET.SubElement(root, 'queries')
        xml_add(queries, 'provider', authorities=AUTHORITY)
        application.set(A + 'label', '网易云音乐 · 参数桥实验')
        activity = xml_add(application, 'activity', name='dev.soundconnect.bridge.BridgeActivity',
                           exported='true', label='360RA 参数桥 · 实验版', theme='@android:style/Theme.Material.Light')
        link = ET.SubElement(activity, 'intent-filter')
        xml_add(link, 'action', name='android.intent.action.VIEW')
        xml_add(link, 'category', name='android.intent.category.DEFAULT')
        xml_add(link, 'category', name='android.intent.category.BROWSABLE')
        xml_add(link, 'data', scheme='netease360ra', host='immersive-audio.sony.com', path='/start-optimize')
        alias = xml_add(application, 'activity-alias', name='dev.soundconnect.bridge.BridgeLauncher',
                        targetActivity='dev.soundconnect.bridge.BridgeActivity', exported='true', label='360RA 参数桥 · 实验版')
        launcher = ET.SubElement(alias, 'intent-filter')
        xml_add(launcher, 'action', name='android.intent.action.MAIN')
        xml_add(launcher, 'category', name='android.intent.category.LAUNCHER')
    tree.write(str(path), encoding='utf-8', xml_declaration=True)


def add_bridge_dex(kind):
    destination = ROOT / 'patch' / kind
    existing = sorted(destination.glob('classes*.dex'))
    indexes = [int(p.stem[7:] or '1') for p in existing]
    index = max(indexes) + 1
    shutil.copy2(ROOT / 'bridge/build/dex/classes.dex', destination / f'classes{index}.dex')
    print(kind, 'bridge dex:', index)


if __name__ == '__main__':
    patch_sony_dex()
    for kind in ('sony', 'netease'):
        patch_manifest(kind)
        add_bridge_dex(kind)
    print('Prepared checked patches. Audio renderer is intentionally not marked implemented.')
