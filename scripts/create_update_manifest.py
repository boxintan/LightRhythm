"""Describe the actual built APK, not a separately maintained version number."""
import argparse
import hashlib
import json
import re
import subprocess
from pathlib import Path


def create_manifest(apk: Path, aapt2: str, download_url: str) -> dict:
    if not download_url.startswith('https://'):
        raise ValueError('APK download URL must use HTTPS')
    badging = subprocess.check_output([aapt2, 'dump', 'badging', str(apk)], text=True, encoding='utf-8')
    package = re.search(r"^package: name='([^']+)' versionCode='(\d+)' versionName='([^']+)'", badging, re.M)
    sdk = re.search(r"^(?:minSdkVersion|sdkVersion):'(\d+)'", badging, re.M)
    if package is None or sdk is None or package[1] != 'com.example.lightrhythm':
        raise ValueError('Unexpected APK metadata')
    return {
        'applicationId': package[1],
        'versionCode': int(package[2]),
        'versionName': package[3],
        'minSdk': int(sdk[1]),
        'apkUrl': download_url,
        'sha256': hashlib.sha256(apk.read_bytes()).hexdigest(),
        'sizeBytes': apk.stat().st_size,
        'notes': '光律团队测试版，包含 main 已合并的最新改动。安装后保留现有应用数据。',
    }


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--apk', type=Path, required=True)
    parser.add_argument('--aapt2', required=True)
    parser.add_argument('--download-url', required=True)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    manifest = create_manifest(args.apk, args.aapt2, args.download_url)
    args.output.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(f"Created update manifest for {manifest['versionName']} ({manifest['versionCode']})")
