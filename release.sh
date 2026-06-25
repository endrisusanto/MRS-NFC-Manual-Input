#!/usr/bin/env bash
set -euo pipefail

need() {
  command -v "$1" >/dev/null 2>&1 || { echo "Missing command: $1" >&2; exit 1; }
}

need git
need node

CURRENT_VERSION="$(node -p "require('./package.json').version")"
IFS=. read -r MAJOR MINOR PATCH <<<"$CURRENT_VERSION"
NEW_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))"

while git rev-parse "v${NEW_VERSION}" >/dev/null 2>&1; do
  IFS=. read -r MAJOR MINOR PATCH <<<"$NEW_VERSION"
  NEW_VERSION="${MAJOR}.${MINOR}.$((PATCH + 1))"
done

TAG="v${NEW_VERSION}"
echo "Releasing ${TAG}"

NEW_VERSION="$NEW_VERSION" node <<'NODE'
const fs = require('fs');
const version = process.env.NEW_VERSION;

for (const file of ['package.json', 'package-lock.json', 'src-tauri/tauri.conf.json']) {
  const json = JSON.parse(fs.readFileSync(file, 'utf8'));
  json.version = version;
  if (file === 'package-lock.json' && json.packages?.['']) json.packages[''].version = version;
  fs.writeFileSync(file, JSON.stringify(json, null, 2) + '\n');
}

const cargo = 'src-tauri/Cargo.toml';
fs.writeFileSync(
  cargo,
  fs.readFileSync(cargo, 'utf8').replace(/^version = ".*"$/m, `version = "${version}"`)
);
NODE

git add -A
git commit -m "Release ${TAG}"
git tag -a "$TAG" -m "Release ${TAG}"
git push origin HEAD
git push origin "$TAG"

echo "Pushed ${TAG}. GitHub Actions will build NSIS, APK, extension zip, and publish artifacts."
