const fs = require('node:fs/promises');
const path = require('node:path');
const crypto = require('node:crypto');
const sharp = require(require.resolve('sharp', { paths: ['/app'] }));

const colors = [[240, 32, 32], [32, 224, 32], [32, 32, 240], [240, 224, 32]];
const formats = ['jpeg', 'png', 'webp', 'heic', 'avif'];

async function verifyPixels(bytes) {
  const { data, info } = await sharp(bytes, { failOn: 'warning' }).removeAlpha().raw().toBuffer({ resolveWithObject: true });
  if (info.width !== 64 || info.height !== 48) throw new Error('Unexpected dimensions');
  for (const [index, [x, y]] of [[8, 8], [48, 8], [8, 36], [48, 36]].entries()) {
    for (let channel = 0; channel < 3; channel++) {
      if (Math.abs(data[(y * 64 + x) * info.channels + channel] - colors[index][channel]) > 24) {
        throw new Error('Unexpected quadrant pixel');
      }
    }
  }
}

async function main() {
  const [mode, directory] = process.argv.slice(2);
  if (mode === 'generate') {
    await fs.mkdir(directory, { recursive: true });
    const pixels = Buffer.alloc(64 * 48 * 3);
    for (let y = 0; y < 48; y++) for (let x = 0; x < 64; x++) {
      const color = colors[(y >= 24 ? 2 : 0) + (x >= 32 ? 1 : 0)];
      color.forEach((value, channel) => { pixels[(y * 64 + x) * 3 + channel] = value; });
    }
    for (const format of ['jpeg', 'png', 'webp', 'avif']) {
      await sharp(pixels, { raw: { width: 64, height: 48, channels: 3 } })
        .toFormat(format, format === 'jpeg' ? { quality: 95 } : { lossless: true })
        .toFile(path.join(directory, `quadrants.${format}`));
    }
    console.log(JSON.stringify({ generated: 4, versions: sharp.versions }));
    return;
  }
  if (mode === 'verify-outputs') {
    const results = [];
    for (const platform of ['windows', 'linux']) for (const format of ['jpeg', 'png', 'webp']) {
      const bytes = await fs.readFile(path.join(directory, platform, 'outputs', `${format}.webp`));
      if ((await sharp(bytes).metadata()).format !== 'webp') throw new Error('Not WebP');
      await verifyPixels(bytes);
      results.push({ platform, inputFormat: format, v2ReadsJavaOutput: true });
    }
    console.log(JSON.stringify({ total: results.length, passed: results.length, failed: 0, results }, null, 2));
    return;
  }
  if (mode !== 'observe') throw new Error('Expected generate, observe or verify-outputs');
  const cases = [];
  for (const format of formats) {
    const bytes = await fs.readFile(path.join(directory, `quadrants.${format}`));
    const result = { format, sha256: crypto.createHash('sha256').update(bytes).digest('hex') };
    try {
      const metadata = await sharp(bytes, { failOn: 'warning' }).metadata();
      result.metadata = { format: metadata.format, width: metadata.width, height: metadata.height, compression: metadata.compression };
      await verifyPixels(bytes);
      result.read = true;
    } catch (error) { result.read = false; result.error = error.message; }
    cases.push(result);
  }
  console.log(JSON.stringify({ engine: 'v2 sharp', versions: sharp.versions, cases }, null, 2));
}
main().catch(error => { console.error(error.message); process.exitCode = 1; });
