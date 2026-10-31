const { chromium } = require('playwright');
(async () => {
  const browser = await chromium.launch();
  const page = await browser.newPage();
  page.on('console', (m) => { if (m.type() === 'error') console.log('console.error:', m.text()); });
  await page.goto('http://localhost:8080/', { waitUntil: 'networkidle' });
  console.log('title:', await page.title());
  console.log('status:', await page.textContent('#status'));
  console.log('t1:', await page.textContent('#t1info'));
  console.log('t3:', await page.textContent('#t3info'));
  // pick Hindi (already default), set reference, upload the wav
  await page.fill('#ref', 'नमस्ते मेरा नाम मयंक है और मैं दिल्ली में रहता हूँ');
  await page.setInputFiles('#file', process.argv[2] || 'build/test-out/tts-hi-piper.wav');
  await page.waitForSelector('#row3 .big', { timeout: 60000 });
  console.log('tier3 text:', await page.textContent('#row3 .big'));
  console.log('tier3 badges:', (await page.$$eval('#row3 .badge', (els) => els.map((e) => e.textContent))).join(' | '));
  console.log('tier3 words:', (await page.$$eval('#row3 .word', (els) => els.length)));
  console.log('tier3 mono:', await page.textContent('#row3 .mono'));
  // TTS via backend
  await page.fill('#ttsText', 'आपका स्वागत है');
  await page.click('#speak3');
  await page.waitForFunction(() => document.querySelector('#ttsInfo').textContent.includes('tier 3'), null, { timeout: 60000 });
  console.log('tts:', await page.textContent('#ttsInfo'));
  const audioSrc = await page.getAttribute('#player', 'src');
  console.log('audio src set:', !!audioSrc);
  // Tamil chip switches language + voices reload
  await page.click('text=தமிழ்');
  await page.waitForTimeout(500);
  console.log('voices for ta:', await page.$$eval('#voice3 option', (o) => o.length));
  await page.screenshot({ path: 'build/test-out/console.png', fullPage: true });
  await browser.close();
})().catch((e) => { console.error('FAILED', e); process.exit(1); });
