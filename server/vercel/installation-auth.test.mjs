import test from 'node:test';
import assert from 'node:assert/strict';
import {generateKeyPairSync,sign,randomBytes} from 'node:crypto';
import {canonical,verifyProof,reserveInstallation} from './installation-auth.mjs';
import {publicConfig,publicPredict} from './public-predictions.mjs';
const now=Date.now();
const env={OPENAI_API_KEY:'test-provider-key',KV_REST_API_URL:'https://quota.example',KV_REST_API_TOKEN:'test-redis-key'};
function proof(raw,time=now,curve='prime256v1') {
 const {privateKey,publicKey}=generateKeyPairSync('ec',{namedCurve:curve});
 const nonce=randomBytes(16).toString('hex');
 return {'x-notouch-key':publicKey.export({format:'der',type:'spki'}).toString('base64url'),
 'x-notouch-time':String(time),'x-notouch-nonce':nonce,
 'x-notouch-signature':sign('sha256',Buffer.from(canonical(time,nonce,raw)),privateKey).toString('base64url')};
}
const raw=()=>Buffer.from(JSON.stringify({text:'안녕하세요',version:1,policy_id:publicConfig(env).policy_id}));
test('valid P256 proof; wrong payload, missing proof, expired clock and wrong curve denied',()=>{
 const body=raw(),headers=proof(body);
 assert.equal(verifyProof(headers,body,now).id.length,64);
 for(const [h,b] of [[headers,Buffer.from('tampered')],[{},body],[proof(body,now-61000),body],[proof(body,now+61000),body],[proof(body,now,'secp384r1'),body]])
  assert.throws(()=>verifyProof(h,b,now),e=>e.status===401);
});
test('public config never needs shared token and binds consent to public policy',()=>{
 assert.equal(publicConfig(env).enabled,true);
 assert.equal(publicConfig({}).enabled,false);
 assert.equal(publicConfig(env).auth_scheme,'installation-p256-v1');
});
test('valid proof and both quota reservations precede fixed provider request',async()=>{
 const body=raw();const calls=[];
 const fetcher=async(url,options)=>{
  calls.push({url,options});
  if(url==='https://quota.example') return Response.json({result:1});
  assert.equal(url,'https://api.openai.com/v1/responses');
  assert.equal(JSON.parse(options.body).store,false);
  return Response.json({status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify({words:['안녕하세요'],continuation:' 반갑습니다.'})}]}]});
 };
 const result=await publicPredict(body,proof(body),'192.0.2.1',env,fetcher,now);
 assert.equal(result.version,1);assert.equal(calls.length,3);
 assert.equal(JSON.parse(calls[0].options.body)[2],'5');
 assert.equal(calls[0].options.body.includes('192.0.2.1'),false);
});
test('replay, limits and Redis failures fail closed without provider call',async()=>{
 for(const [value,status] of [[-1,409],[0,429],[null,503]]) {
  let calls=0;const body=raw();
  await assert.rejects(publicPredict(body,proof(body),'192.0.2.1',env,async url=>{
   calls++;assert.equal(url,'https://quota.example');return Response.json({result:value});
  },now),e=>e.status===status);
  assert.equal(calls,1);
 }
});
test('invalid proof, consent, payload and address never call provider',async()=>{
 const fail=()=>{throw Error('unexpected network request');};const body=raw();
 await assert.rejects(publicPredict(body,{},'192.0.2.1',env,fail,now),e=>e.status===401);
 const stale=Buffer.from(JSON.stringify({text:'test',version:1,policy_id:'old'}));
 await assert.rejects(publicPredict(stale,proof(stale),'192.0.2.1',env,fail,now),e=>e.status===412);
 const invalid=Buffer.from(JSON.stringify({text:'',version:1,policy_id:publicConfig(env).policy_id}));
 await assert.rejects(publicPredict(invalid,proof(invalid),'192.0.2.1',env,fail,now),e=>e.status===422);
 await assert.rejects(publicPredict(body,proof(body),'',env,fail,now),e=>e.status===503);
});

import handler from './api/public-predictions.mjs';
import {Readable} from 'node:stream';
test('HTTP adapter rejects wrong method, media type, oversize and unsigned body',async()=>{
 async function call(method,headers,body) {
  const req=Readable.from([Buffer.from(body)]);req.method=method;req.headers=headers;
  const res={setHeader(){},status(n){this.code=n;return this;},json(value){this.body=value;return this;}};
  await handler(req,res);return res.code;
 }
 assert.equal(await call('GET',{},''),405);
 assert.equal(await call('POST',{},'{}'),415);
 assert.equal(await call('POST',{'content-type':'application/json'},'a'.repeat(16385)),413);
 assert.equal(await call('POST',{'content-type':'application/json'},'{}'),401);
});
