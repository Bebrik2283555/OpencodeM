import { spawn } from 'node:child_process';
import { mkdir, readFile, unlink } from 'node:fs/promises';
import path from 'node:path';
import { deflateSync } from 'node:zlib';

const crcTable = new Uint32Array(256);
for (let n = 0; n < 256; n++) {
  let c = n;
  for (let k = 0; k < 8; k++) c = (c & 1) ? (0xedb88320 ^ (c >>> 1)) : (c >>> 1);
  crcTable[n] = c;
}

function crc32(b) {
  let c = -1;
  for (let i = 0; i < b.length; i++) c = (c >>> 8) ^ crcTable[(c ^ b[i]) & 0xff];
  return (c ^ -1) >>> 0;
}

function pngChunk(type, data) {
  const typeBuf = Buffer.from(type);
  const len = Buffer.alloc(4);
  len.writeUInt32BE(data.length, 0);
  const toCrc = Buffer.concat([typeBuf, data]);
  const crc = Buffer.alloc(4);
  crc.writeUInt32BE(crc32(toCrc), 0);
  return Buffer.concat([len, toCrc, crc]);
}

export function compressRawScreenshot(buf, options = {}) {
  // If already PNG format:
  if (buf.length >= 8 && buf[0] === 0x89 && buf[1] === 0x50 && buf[2] === 0x4E && buf[3] === 0x47) {
    return { bytes: buf, width: 0, height: 0, origW: 0, origH: 0, scale: 1 };
  }
  if (buf.length < 16) throw new Error('Invalid screenshot buffer: too short');
  const origW = buf.readUInt32LE(0);
  const origH = buf.readUInt32LE(4);
  if (origW === 0 || origH === 0 || buf.length < 16 + origW * origH * 4) {
    throw new Error(`Corrupted screenshot buffer: ${origW}x${origH}, size ${buf.length}`);
  }
  const raw = buf.subarray(16);

  const scale = Math.max(1, Math.min(options.scale ?? 2, 8));
  let cropX = 0, cropY = 0, cropW = origW, cropH = origH;
  if (options.crop) {
    cropX = Math.max(0, Math.min(options.crop.x ?? 0, origW - 1));
    cropY = Math.max(0, Math.min(options.crop.y ?? 0, origH - 1));
    cropW = Math.max(1, Math.min(options.crop.width ?? (origW - cropX), origW - cropX));
    cropH = Math.max(1, Math.min(options.crop.height ?? (origH - cropY), origH - cropY));
  }

  const dw = Math.max(1, Math.floor(cropW / scale));
  const dh = Math.max(1, Math.floor(cropH / scale));
  const stride = dw * 4;
  const pixels = Buffer.alloc(dh * (stride + 1));

  for (let y = 0; y < dh; y++) {
    const rowOffset = y * (stride + 1);
    pixels[rowOffset] = 0; // Filter type: None
    const srcY = cropY + y * scale;
    for (let x = 0; x < dw; x++) {
      const srcX = cropX + x * scale;
      const srcIdx = (srcY * origW + srcX) * 4;
      const dstIdx = rowOffset + 1 + x * 4;
      pixels[dstIdx] = raw[srcIdx];
      pixels[dstIdx + 1] = raw[srcIdx + 1];
      pixels[dstIdx + 2] = raw[srcIdx + 2];
      pixels[dstIdx + 3] = raw[srcIdx + 3];
    }
  }

  const idat = deflateSync(pixels, { level: 6 });
  const sig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);
  const ihdr = Buffer.alloc(13);
  ihdr.writeUInt32BE(dw, 0);
  ihdr.writeUInt32BE(dh, 4);
  ihdr[8] = 8;
  ihdr[9] = 6;
  ihdr[10] = 0;
  ihdr[11] = 0;
  ihdr[12] = 0;

  const png = Buffer.concat([
    sig,
    pngChunk('IHDR', ihdr),
    pngChunk('IDAT', idat),
    pngChunk('IEND', Buffer.alloc(0)),
  ]);

  return {
    bytes: png,
    width: dw,
    height: dh,
    origW,
    origH,
    scale,
    crop: options.crop ? { x: cropX, y: cropY, width: cropW, height: cropH } : null,
  };
}

export function phoneCommand(input, screenshot) {
  const number = key => {
    const n = input[key];
    if (!Number.isInteger(n) || n < 0 || n > 20000) throw new Error(`Invalid ${key}`);
    return String(n);
  };
  switch (input.action) {
    case 'screenshot': return ['/system/bin/screencap', screenshot];
    case 'tap': return ['/system/bin/input', 'tap', number('x'), number('y')];
    case 'swipe': return ['/system/bin/input', 'swipe', number('x'), number('y'), number('x2'), number('y2'), String(Math.min(input.duration ?? 300, 3000))];
    case 'text': {
      if (typeof input.text !== 'string' || input.text.length === 0) throw new Error('Specify text to type');
      return ['/system/bin/input', 'text', input.text.replaceAll(' ', '%s')];
    }
    case 'key': {
      const allowed = [
        'BACK', 'HOME', 'ENTER', 'APP_SWITCH', 'VOLUME_UP', 'VOLUME_DOWN',
        'TAB', 'DEL', 'FORWARD_DEL', 'ESCAPE', 'DPAD_UP', 'DPAD_DOWN', 'DPAD_LEFT', 'DPAD_RIGHT', 'SPACE'
      ];
      if (!allowed.includes(input.key)) throw new Error('Unsupported key: ' + input.key);
      return ['/system/bin/input', 'keyevent', 'KEYCODE_' + input.key];
    }
    case 'open': {
      if (!input.url || !/^(https?|file|content):\/\/.+/i.test(input.url)) throw new Error('Invalid URL or URI for open action');
      return ['/system/bin/am', 'start', '-a', 'android.intent.action.VIEW', '-d', input.url];
    }
    case 'launch': {
      if (!/^[a-zA-Z][a-zA-Z0-9_]*(\.[a-zA-Z0-9_]+)+$/.test(input.package ?? '')) throw new Error('Invalid package name');
      return ['/system/bin/monkey', '-p', input.package, '-c', 'android.intent.category.LAUNCHER', '1'];
    }
    case 'dumpsys': {
      if (!/^[a-zA-Z0-9._-]+$/.test(input.service ?? '')) throw new Error('Specify one dumpsys service');
      return ['/system/bin/dumpsys', input.service];
    }
    case 'apps': return ['/system/bin/pm', 'list', 'packages', '-3'];
    default: throw new Error('Unsupported phone action');
  }
}

export async function registerPhone(ctx, turbo = false) {
  if (process.env.MARGY_PHONE_CONTROL !== 'true') return;
  await ctx.tool.transform(editor => editor.add({
    name: 'phone', options: { codemode: false, permission: 'phone' },
    description: 'Control this Android phone through root. Screenshots return an image (default 2x downscaled, optional scale and crop). Ask before actions affecting apps. Use normal read/write tools for files.',
    input: { type: 'object', properties: {
      action: { type: 'string', enum: ['screenshot','tap','swipe','key','text','open','launch','dumpsys','apps'] },
      x: { type: 'integer', minimum: 0, maximum: 20000 }, y: { type: 'integer', minimum: 0, maximum: 20000 },
      x2: { type: 'integer', minimum: 0, maximum: 20000 }, y2: { type: 'integer', minimum: 0, maximum: 20000 },
      duration: { type: 'integer', minimum: 1, maximum: 3000 }, key: { type: 'string' }, text: { type: 'string' }, url: { type: 'string' }, package: { type: 'string' }, service: { type: 'string' },
      scale: { type: 'integer', minimum: 1, maximum: 8 },
      crop: {
        type: 'object',
        properties: {
          x: { type: 'integer', minimum: 0 },
          y: { type: 'integer', minimum: 0 },
          width: { type: 'integer', minimum: 1 },
          height: { type: 'integer', minimum: 1 },
        },
        required: ['x', 'y', 'width', 'height'],
        additionalProperties: false,
      },
    }, required: ['action'], additionalProperties: false },
    execute: async (input, context) => {
      const folder = path.join(process.env.XDG_CACHE_HOME, 'phone-screens');
      const image = path.join(folder, `screen-${Date.now()}.raw`);
      const args = phoneCommand(input, image);
      if (!turbo) await ctx.permission.assert({ sessionID: context.sessionID, agent: context.agent,
        source: { type: 'tool', messageID: context.messageID, id: context.id }, action: 'phone',
        resources: [input.action + (input.package ? ':' + input.package : input.service ? ':' + input.service : '')], save: [input.action] });
      if (input.action === 'screenshot') await mkdir(folder, { recursive: true });
      const command = args.map(arg => "'" + arg.replaceAll("'", "'\\''") + "'").join(' ');
      const output = await new Promise((resolve, reject) => {
        const child = spawn('/system/bin/su', ['-c', command], { signal: context.signal, timeout: 25000, killSignal: 'SIGKILL' });
        let text = '';
        child.stdout.on('data', data => { text = (text + data).slice(-32000); });
        child.stderr.on('data', data => { text = (text + data).slice(-32000); });
        child.once('error', err => {
          if (err && err.code === 'ENOENT') reject(new Error('Root binary (/system/bin/su) is not available on this device.'));
          else reject(err);
        });
        child.once('close', code => {
          if (code === 0) return resolve(text);
          if (text.includes('Permission denied') || text.includes('rejected')) {
            reject(new Error('Root access denied by Superuser manager (Magisk/KernelSU). Grant root to OpencodeM.'));
          } else {
            reject(new Error(text || `Phone command exited: ${code}`));
          }
        });
      });
      if (input.action !== 'screenshot') return { content: output || 'Done' };
      const rawBytes = await readFile(image).finally(() => unlink(image).catch(() => {}));
      const { bytes, width, height, origW, origH, scale, crop } = compressRawScreenshot(rawBytes, {
        scale: input.scale,
        crop: input.crop,
      });
      if (bytes.length > 2_500_000) {
        return { content: `Screenshot (${width}x${height}, ${bytes.length} bytes) is too large to attach inline.` };
      }
      const desc = origW > 0
        ? `Screen: ${origW}x${origH}. Screenshot: ${width}x${height} (scale 1/${scale}${crop ? `, crop ${crop.width}x${crop.height} at ${crop.x},${crop.y}` : ''}). Coordinates for tap/swipe are in screen pixels (0..${origW}, 0..${origH}).`
        : 'Screenshot captured.';
      return {
        content: [
          { type: 'text', text: desc },
          { type: 'file', uri: 'data:image/png;base64,' + bytes.toString('base64'), mime: 'image/png', name: 'screen.png' },
        ],
      };
    },
  }));
}
