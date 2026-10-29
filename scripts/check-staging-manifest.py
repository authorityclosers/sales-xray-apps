#!/usr/bin/env python3
"""Check final merged staging manifest against Play, including future native changes."""
from pathlib import Path
import sys
import xml.etree.ElementTree as ET

A = '{http://schemas.android.com/apk/res/android}'


def verify(play, staging):
    p, s = ET.parse(play).getroot(), ET.parse(staging).getroot()
    assert s.get('package') == 'com.authorityclosers.salesxray.staging'
    assert p.get('package') == 'com.authorityclosers.salesxray'
    def attributes(element, package):
        values = dict(element.attrib)
        # AndroidX generates a signature permission scoped to each applicationId.
        # These names must differ between packages; all platform permissions stay exact.
        if values.get(A + 'name') == package + '.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION':
            values[A + 'name'] = '$SELF.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION'
        return tuple(sorted(values.items()))
    for tag in ['permission', 'uses-permission', 'uses-feature']:
        canonical = lambda root: sorted(attributes(e, root.get('package')) for e in root.findall(tag))
        assert canonical(p) == canonical(s), f'{tag} differs from Play'
    pa, sa = p.find('application'), s.find('application')
    assert sa.get(A + 'label') == 'Sales X-Ray Staging'
    assert sa.get(A + 'allowBackup') == 'false'
    assert 'staging_icon' in sa.get(A + 'icon', '')
    for tag in ['service', 'receiver']:
        canonical = lambda app: sorted(ET.tostring(e, encoding='unicode') for e in app.findall(tag))
        assert canonical(pa) == canonical(sa), f'{tag} differs from Play'
    assert sa.find('provider').get(A + 'authorities').startswith('com.authorityclosers.salesxray.staging.')
    print('Staging package, label, icon, isolation, permissions and native services verified against Play')


if __name__ == '__main__':
    root = Path(sys.argv[1])
    def manifest(variant):
        paths = list(root.glob(f'merged_manifest/{variant}/**/AndroidManifest.xml'))
        assert len(paths) == 1, f'Expected one merged manifest for {variant}, got {paths}'
        return paths[0]
    verify(manifest('playDebug'), manifest('stagingDebug'))
