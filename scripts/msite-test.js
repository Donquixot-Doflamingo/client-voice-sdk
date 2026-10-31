// Headless test of the mobile test site (proxy/web/msite.html): tier 2 + tier 3 STT from a WAV, TTS via tier 2.
// node scripts/msite-test.js [base=http://localhost:8080] [wav=build/test-out/tts-hi-piper.wav]
const { chromium } = require('playwright');
(async () => {
  const base = process.argv[2] || 'http://localhost:8080', wav = process.argv[3] || 'build/test-out/tts-hi-piper.wav';
  const b = await chromium.launch(); const page = await b.newPage({ viewport: { width: 390, height: 844 } });
  const errors = []; page.on('pageerror', e => errors.push(e.message));
  await page.goto(base + '/m');
  await page.waitForFunction(() => document.querySelector('#i2').textContent.includes('stt'), null, { timeout: 15000 });
  console.log('tier1:', await page.textContent('#i1')); console.log('tier2:', await page.textContent('#i2'));
  // configure tier 3 = custom backend pointing at the same server (different URL form), apply
  await page.click('#settings summary'); await page.fill('#url3', 'http://127.0.0.1:8080'); await page.click('#apply');
  await page.waitForFunction(() => document.querySelector('#i3').textContent.includes('stt'), null, { timeout: 15000 });
  console.log('tier3:', await page.textContent('#i3'));
  await page.click('.tab[data-mode="all"]'); await page.fill('#ref', 'आपका स्वागत है कृपया अपना खाता नंबर बताइए');
  await page.setInputFiles('#file', wav);
  await page.waitForFunction(() => document.querySelector('#row2 .big') && document.querySelector('#row3 .big'), null, { timeout: 60000 });
  for (const t of [2, 3]) console.log(`row${t}:`, (await page.textContent(`#row${t}`)).replace(/\s+/g, ' ').trim());
  // Regression: Auto + English speech must come back as English (it used to come back as Japanese/Devanagari).
  await page.click('[data-lang="auto"]'); await page.fill('#ref', '');
  for (const [file, want, wantText] of [['build/test-out/lid/en-hello-us.wav', 'en', /hello/i], ['build/test-out/lid/en-howareyou-us.wav', 'en', /how are you/i], ['build/test-out/lid/hi-balance.wav', 'hi', /अकाउंट|चेक/]]) {
    await page.evaluate(() => { document.querySelector('#results').innerHTML = ''; });
    await page.setInputFiles('#file', file);
    await page.waitForFunction(() => document.querySelector('#row2 .big'), null, { timeout: 60000 });
    const row = (await page.textContent('#row2')).replace(/\s+/g, ' ').trim();
    const ok = row.includes(' · ' + want) && wantText.test(row);
    console.log(`auto ${file.split('/').pop()}: ${ok ? 'OK' : 'WRONG'} — ${row.slice(0, 140)}`);
    if (!ok) errors.push('auto-language regression: ' + file);
  }
  await page.click('[data-lang="hi"]');
  await page.click('.tab[data-mode="2"]'); await page.click('#speak');
  await page.waitForFunction(() => /KB/.test(document.querySelector('#ttsInfo').textContent) || /error/.test(document.querySelector('#ttsInfo').textContent), null, { timeout: 90000 });
  console.log('tts:', await page.textContent('#ttsInfo'));
  await page.screenshot({ path: 'build/test-out/msite.png', fullPage: true });
  console.log('page errors:', errors.length ? errors : 'none');
  await b.close();
  const ok = errors.length === 0 && /KB/.test(await Promise.resolve(''))===false;
  process.exit(errors.length ? 1 : 0);
})().catch(e => { console.error('FAILED', e.message); process.exit(1); });
