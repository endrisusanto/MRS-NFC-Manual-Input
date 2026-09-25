import fs from 'fs';
import path from 'path';

const distDir = path.resolve('dist');
if (!fs.existsSync(distDir)) {
  fs.mkdirSync(distDir, { recursive: true });
}

// 1. Check if Tauri generated latest.json in bundle dir
const bundleDir = path.resolve('src-tauri/target/release/bundle');

function findFile(dir, targetName) {
  if (!fs.existsSync(dir)) return null;
  const files = fs.readdirSync(dir, { withFileTypes: true });
  for (const f of files) {
    const full = path.join(dir, f.name);
    if (f.isDirectory()) {
      const res = findFile(full, targetName);
      if (res) return res;
    } else if (f.name === targetName) {
      return full;
    }
  }
  return null;
}

const tauriLatestPath = findFile(bundleDir, 'latest.json');
if (tauriLatestPath) {
  console.log(`Found Tauri-generated latest.json at ${tauriLatestPath}`);
  const content = fs.readFileSync(tauriLatestPath, 'utf8');
  fs.writeFileSync(path.join(distDir, 'latest.json'), content, 'utf8');
  console.log('Copied to dist/latest.json without BOM:');
  console.log(content);
  process.exit(0);
}

// 2. Otherwise, construct latest.json from .sig and bundle files in dist/
const distFiles = fs.readdirSync(distDir);
const sigFile = distFiles.find(f => f.endsWith('.sig'));

let bundleFile = null;
if (sigFile) {
  const baseName = sigFile.slice(0, -4);
  if (distFiles.includes(baseName)) {
    bundleFile = baseName;
  }
}

if (!bundleFile) {
  bundleFile = distFiles.find(f => f.endsWith('.nsis.zip') || f.endsWith('.msi.zip') || f.endsWith('.exe') || f.endsWith('.msi'));
}

if (!sigFile || !bundleFile) {
  console.warn('Warning: Missing .sig or bundle file in dist/');
  console.warn('dist contents:', distFiles);
  process.exit(0);
}

const sigContent = fs.readFileSync(path.join(distDir, sigFile), 'utf8').trim();
const tag = process.env.GITHUB_REF_NAME || process.env.TAG || ('v' + JSON.parse(fs.readFileSync('package.json', 'utf8')).version);
const ver = tag.replace(/^v/, '');
const date = new Date().toISOString();
const uploadName = bundleFile.replace(/\s+/g, '.');
const downloadUrl = `https://github.com/endrisusanto/MRS-NFC-Manual-Input/releases/download/${tag}/${uploadName}`;

const manifest = {
  version: ver,
  notes: `Release ${tag}`,
  pub_date: date,
  platforms: {
    'windows-x86_64': {
      signature: sigContent,
      url: downloadUrl
    },
    'windows-x64': {
      signature: sigContent,
      url: downloadUrl
    }
  }
};

const jsonStr = JSON.stringify(manifest, null, 2);
fs.writeFileSync(path.join(distDir, 'latest.json'), jsonStr, 'utf8');
console.log(`Successfully generated dist/latest.json for ${bundleFile} without BOM:`);
console.log(jsonStr);
