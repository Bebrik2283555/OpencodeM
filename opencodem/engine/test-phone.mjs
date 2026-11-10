import { test } from 'node:test';
import assert from 'node:assert/strict';
import { compressRawScreenshot, phoneCommand, registerPhone } from '../app/src/main/assets/phone-tools.mjs';

test('phone commands validate coordinates, package names and dumpsys scope', () => {
  assert.deepEqual(phoneCommand({ action:'tap',x:10,y:20 }), ['/system/bin/input','tap','10','20']);
  assert.throws(()=>phoneCommand({action:'tap',x:-1,y:0}));
  assert.throws(()=>phoneCommand({action:'launch',package:'app; rm -rf /'}));
  assert.throws(()=>phoneCommand({action:'dumpsys',service:'activity;id'}));
  assert.throws(()=>phoneCommand({action:'key',key:'POWER'}));
  assert.deepEqual(phoneCommand({action:'key',key:'BACK'}),['/system/bin/input','keyevent','KEYCODE_BACK']);
  assert.deepEqual(phoneCommand({action:'key',key:'TAB'}),['/system/bin/input','keyevent','KEYCODE_TAB']);
  assert.deepEqual(phoneCommand({action:'key',key:'DEL'}),['/system/bin/input','keyevent','KEYCODE_DEL']);
  assert.deepEqual(phoneCommand({action:'text',text:'hello world'}),['/system/bin/input','text','hello%sworld']);
  assert.deepEqual(phoneCommand({action:'open',url:'https://github.com'}),['/system/bin/am','start','-a','android.intent.action.VIEW','-d','https://github.com']);
  assert.throws(()=>phoneCommand({action:'open',url:'javascript:alert(1)'}));
  assert.deepEqual(phoneCommand({action:'screenshot'},'/tmp/s.raw'),['/system/bin/screencap','/tmp/s.raw']);
});

test('compressRawScreenshot encodes raw RGBA buffer to valid PNG with downscaling and crop', () => {
  const fake = Buffer.alloc(16 + 100 * 200 * 4);
  fake.writeUInt32LE(100, 0);
  fake.writeUInt32LE(200, 4);
  fake.writeUInt32LE(1, 8);
  fake.writeUInt32LE(1, 12);

  const res2x = compressRawScreenshot(fake, { scale: 2 });
  assert.equal(res2x.width, 50);
  assert.equal(res2x.height, 100);
  assert.equal(res2x.origW, 100);
  assert.equal(res2x.origH, 200);
  assert.equal(res2x.scale, 2);
  // Valid PNG signature
  assert.deepEqual(Array.from(res2x.bytes.subarray(0, 8)), [0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A]);

  const resCrop = compressRawScreenshot(fake, { scale: 1, crop: { x: 10, y: 20, width: 30, height: 40 } });
  assert.equal(resCrop.width, 30);
  assert.equal(resCrop.height, 40);
  assert.deepEqual(resCrop.crop, { x: 10, y: 20, width: 30, height: 40 });

  // Pass-through for existing PNG
  const pngSig = Buffer.from([0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 1, 2, 3]);
  assert.equal(compressRawScreenshot(pngSig).bytes, pngSig);

  // Errors on corrupted buffer
  assert.throws(() => compressRawScreenshot(Buffer.alloc(8)));
});

test('phone tools stay unavailable until enabled', async()=>{
  const previous=process.env.MARGY_PHONE_CONTROL;
  process.env.MARGY_PHONE_CONTROL='false';
  try { await registerPhone({tool:{transform:()=>{throw new Error('Registered while disabled')}}}); }
  finally { if(previous===undefined)delete process.env.MARGY_PHONE_CONTROL;else process.env.MARGY_PHONE_CONTROL=previous; }
});

test('permission rejection stops phone execution before su', async()=>{
  const previous=process.env.MARGY_PHONE_CONTROL;
  process.env.MARGY_PHONE_CONTROL='true';
  let tool;
  try {
    await registerPhone({tool:{transform:async fn=>fn({add:value=>{tool=value}})},permission:{assert:async()=>{throw new Error('denied')}}});
    process.env.XDG_CACHE_HOME ??= '/tmp';
    await assert.rejects(tool.execute({action:'apps'},{sessionID:'ses_test',agent:'build',messageID:'msg_test',id:'call_test'}),/denied/);
  } finally { if(previous===undefined)delete process.env.MARGY_PHONE_CONTROL;else process.env.MARGY_PHONE_CONTROL=previous; }
});
