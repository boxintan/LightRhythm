#!/usr/bin/env bash
set -euo pipefail

if [ "$(gh api "repos/$GH_REPO/git/ref/heads/team-preview" --jq '.object.sha')" != "$GITHUB_SHA" ]; then
    echo 'A newer team-preview commit exists; skipping publication.'
    exit 0
fi

channel=team-preview-download
channel_ref="refs/heads/$channel"
site="$RUNNER_TEMP/$channel"
repo_url="https://github.com/$GH_REPO"
git init --quiet --initial-branch="$channel" "$site"
git -C "$site" remote add origin "$repo_url.git"
gh auth setup-git --hostname github.com
existing_ref="$(git -C "$site" ls-remote --heads origin "$channel_ref")"
if [ -n "$existing_ref" ]; then
    git -C "$site" fetch --depth=1 origin "$channel_ref"
    git -C "$site" checkout --quiet -B "$channel" FETCH_HEAD
fi

version="$((100000 + GITHUB_RUN_NUMBER))"
if [ -f "$site/update.json" ] && [ "$(jq -e '.versionCode' "$site/update.json")" -ge "$version" ]; then
    echo 'The download page already offers this version or a newer one.'
    exit 0
fi

tag="team-preview-${GITHUB_RUN_NUMBER}-${GITHUB_RUN_ATTEMPT}"
apk_url="$repo_url/releases/download/$tag/LightRhythm-preview.apk"
python3 scripts/create_update_manifest.py \
    --apk dist/LightRhythm-preview.apk \
    --aapt2 "$ANDROID_HOME/build-tools/36.0.0/aapt2" \
    --download-url "$apk_url" --channel team-preview --output dist/update.json
jq -e --argjson version "$version" '.versionCode == $version' dist/update.json > /dev/null
(cd dist && sha256sum LightRhythm-preview.apk > LightRhythm-preview.apk.sha256)

cat > "$site/README.md" <<EOF
# 光律预览版

[下载安卓预览版 APK]($apk_url)

首次安装后，打开手机上的「光律预览版」，点「检查更新」即可下载后续预览。安装或更新时，由 Android 确认。

构建 #$GITHUB_RUN_NUMBER · [待合并分支提交]($repo_url/commit/$GITHUB_SHA) · [构建记录]($repo_url/actions/runs/$GITHUB_RUN_ID)
EOF
cp dist/update.json "$site/update.json"

# Anchor the release tag to main so GitHub Actions can create it even when the
# preview commit changes a workflow. The exact APK source is linked above.
gh release create "$tag" --target main --draft --prerelease --latest=false \
    --title "光律预览版 #$GITHUB_RUN_NUMBER" --notes-file "$site/README.md" \
    dist/LightRhythm-preview.apk dist/LightRhythm-preview.apk.sha256 dist/update.json
gh release edit "$tag" --draft=false --prerelease --latest=false

# Keep the visible link and in-app manifest together in one commit. The previous
# download continues to work if packaging, publication, or this push fails.
if [ "$(gh api "repos/$GH_REPO/git/ref/heads/team-preview" --jq '.object.sha')" != "$GITHUB_SHA" ]; then
    echo 'A newer team-preview commit exists; keeping the current download page.'
    exit 0
fi
git -C "$site" add README.md update.json
git -C "$site" -c user.name=github-actions -c user.email='41898282+github-actions[bot]@users.noreply.github.com' \
    commit --quiet -m "Publish team preview #$GITHUB_RUN_NUMBER"
git -C "$site" push origin "HEAD:$channel_ref"
echo "[手机下载预览版]($repo_url/blob/$channel/README.md)" >> "$GITHUB_STEP_SUMMARY"
