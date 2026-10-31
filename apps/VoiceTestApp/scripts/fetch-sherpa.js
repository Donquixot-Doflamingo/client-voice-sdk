#!/usr/bin/env node
/**
 * Downloads the sherpa-onnx 1.13.8 runtime binaries the native module links against.
 * Runs on `npm install` (postinstall) and is safe to re-run; skips files that exist.
 *   - Android: third_party/sherpa-onnx/libs/sherpa-onnx-1.13.8.aar
 *   - iOS:     modules/indic-voice/ios/Frameworks/{sherpa-onnx,onnxruntime}.xcframework
 * Set SKIP_SHERPA_FETCH=1 to skip (e.g. in CI jobs that only type-check).
 */
const fs = require('fs');
const path = require('path');
const { execSync } = require('child_process');

const V = '1.13.8';
const ORT = '1.28.2';
const APP = path.resolve(__dirname, '..');
const ROOT = path.resolve(APP, '..', '..');
const LIBS = path.join(ROOT, 'third_party', 'sherpa-onnx', 'libs');
const IOS_FW = path.join(APP, 'modules', 'indic-voice', 'ios', 'Frameworks');

if (process.env.SKIP_SHERPA_FETCH === '1') { console.log('[fetch-sherpa] skipped'); process.exit(0); }

async function download(url, dest) {
  if (fs.existsSync(dest) && fs.statSync(dest).size > 0) { console.log(`[fetch-sherpa] have ${path.basename(dest)}`); return; }
  fs.mkdirSync(path.dirname(dest), { recursive: true });
  console.log(`[fetch-sherpa] downloading ${url}`);
  const tmpCurl = dest + '.part';
  try { // curl is present on macOS/Linux/EAS/GitHub runners and honours proxies; fall back to fetch() without it
    execSync(`curl -fsSL --retry 3 -o "${tmpCurl}" "${url}"`, { stdio: 'ignore' });
    fs.renameSync(tmpCurl, dest);
    return;
  } catch (_) { try { fs.unlinkSync(tmpCurl); } catch (__) { /* ignore */ } }
  const res = await fetch(url, { headers: { 'User-Agent': 'indicvoice-fetch' }, redirect: 'follow' });
  if (!res.ok) throw new Error(`HTTP ${res.status} for ${url}`);
  const tmp = dest + '.part';
  const { Readable } = require('stream');
  const { pipeline } = require('stream/promises');
  await pipeline(Readable.fromWeb(res.body), fs.createWriteStream(tmp));
  fs.renameSync(tmp, dest);
}

function unzip(zip, dir) {
  fs.mkdirSync(dir, { recursive: true });
  const tries = [`unzip -qo "${zip}" -d "${dir}"`, `tar -xf "${zip}" -C "${dir}"`, `python3 -c "import zipfile,sys; zipfile.ZipFile(sys.argv[1]).extractall(sys.argv[2])" "${zip}" "${dir}"`];
  for (const cmd of tries) { try { execSync(cmd, { stdio: 'ignore' }); return; } catch (_) { /* next */ } }
  throw new Error(`could not extract ${zip}`);
}

(async () => {
  const platform = process.env.SHERPA_FETCH_PLATFORM || 'all'; // android | ios | all
  try {
    if (platform === 'android' || platform === 'all') {
      await download(`https://github.com/k2-fsa/sherpa-onnx/releases/download/v${V}/sherpa-onnx-${V}.aar`, path.join(LIBS, `sherpa-onnx-${V}.aar`));
    }
    if (platform === 'ios' || platform === 'all') {
      const z1 = path.join(IOS_FW, `sherpa-onnx-v${V}-ios-static.xcframework.zip`);
      const z2 = path.join(IOS_FW, `onnxruntime-ios-static-xcframework-${ORT}.xcframework.zip`);
      await download(`https://github.com/k2-fsa/sherpa-onnx/releases/download/xcframework/sherpa-onnx-v${V}-ios-static.xcframework.zip`, z1);
      await download(`https://github.com/csukuangfj/onnxruntime-libs/releases/download/v${ORT}/onnxruntime-ios-static-xcframework-${ORT}.xcframework.zip`, z2);
      if (!fs.existsSync(path.join(IOS_FW, 'sherpa-onnx.xcframework'))) unzip(z1, IOS_FW);
      if (!fs.existsSync(path.join(IOS_FW, 'onnxruntime.xcframework'))) unzip(z2, IOS_FW);
    }
    console.log('[fetch-sherpa] done');
  } catch (e) {
    // Never fail npm install because of a download problem; the native build will say what is missing.
    console.warn(`[fetch-sherpa] WARNING: ${e.message}. Re-run "node scripts/fetch-sherpa.js" with internet access.`);
  }
})();
