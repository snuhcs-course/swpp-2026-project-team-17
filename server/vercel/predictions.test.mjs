import test from 'node:test';
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import {config,predict} from './predictions.mjs';
const token='a'.repeat(43),auth='Bearer '+token;
const env={NOTOUCH_PREDICTION_LIMITS:'on',OPENAI_API_KEY:'mock',NOTOUCH_TOKEN_SHA256:createHash('sha256').update(token).digest('hex'),KV_REST_API_URL:'https://redis.example',KV_REST_API_TOKEN:'mock'};
const body=()=>({text:'안녕하',version:7,policy_id:config(env).policy_id});
test('auth, consent and fields fail before a paid request',async()=>{
 const no=()=>{throw Error('unexpected fetch')};
 await assert.rejects(predict(body(),'bad',env,no),{status:401});
 await assert.rejects(predict({...body(),policy_id:'old'},auth,env,no),{status:412});
 await assert.rejects(predict({...body(),model:'injected'},auth,env,no),{status:422});
});
test('prediction uses existing server key and enforces shared quota',async()=>{
 let calls=0;
 const fetcher=async(url,opts)=>{
  calls++;
  if(url==='https://redis.example') return {ok:true,json:async()=>({result:1})};
  assert.equal(url,'https://api.openai.com/v1/responses');assert.equal(opts.headers.Authorization,'Bearer mock');
  const payload=JSON.parse(opts.body);assert.equal(payload.store,false);assert.equal(payload.model,'gpt-5.4');
  return {ok:true,json:async()=>({status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify({words:['안녕하세요'],continuation:''})}]}]})};
 };
 assert.deepEqual(await predict(body(),auth,env,fetcher),{version:7,words:['안녕하세요'],continuation:''});assert.equal(calls,2);
});
test('shared quota denies before provider',async()=>{
 let calls=0;await assert.rejects(predict(body(),auth,env,async()=>{calls++;return {ok:true,json:async()=>({result:0})}}),{status:429});assert.equal(calls,1);
});
test('provider billing failures expose no upstream body',async()=>{
 const fetcher=async url=>url==='https://redis.example'?{ok:true,json:async()=>({result:1})}:{ok:false,json:async()=>({error:{code:'credit_balance_exhausted',message:'secret'}})};
 await assert.rejects(predict(body(),auth,env,fetcher),{status:503,message:'provider_quota_exhausted'});
});

test('unfinished tokens get complete alternatives, never append-only syllables',async()=>{
 const fetcher=async(url,opts)=>{
  if(url==='https://redis.example') return {ok:true,json:async()=>({result:1})};
  const payload=JSON.parse(opts.body);
  assert.match(payload.instructions,/도와줘/);
  return {ok:true,json:async()=>({status:'completed',output:[{type:'message',content:[{type:'output_text',text:JSON.stringify({words:['도와줘','도와줄게'],continuation:'와'})}]}]})};
 };
 assert.deepEqual(await predict({...body(),text:'지금 도'},auth,env,fetcher),{version:7,words:['도와줘','도와줄게'],continuation:''});
 assert.deepEqual(await predict({...body(),text:'지금 '},auth,env,fetcher),{version:7,words:[],continuation:'와'});
});
