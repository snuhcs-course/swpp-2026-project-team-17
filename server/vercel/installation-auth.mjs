import {createHash, createPublicKey, verify} from 'node:crypto';
import {isIP} from 'node:net';
import {HttpError} from './core.mjs';
export const sha256 = data => createHash('sha256').update(data).digest('hex');
export const canonical = (time,nonce,body) => `POST\n/v2/predictions\n${time}\n${nonce}\n${sha256(body)}`;
export function verifyProof(headers, raw, now=Date.now()) {
  try {
    const key=headers['x-notouch-key'], time=headers['x-notouch-time'];
    const nonce=headers['x-notouch-nonce'], signature=headers['x-notouch-signature'];
    if(typeof key!=='string'|| !/^[A-Za-z0-9_-]{120,128}$/.test(key) ||
       typeof time!=='string'|| !/^[0-9]{13}$/.test(time) || Math.abs(now-Number(time))>60000 ||
       typeof nonce!=='string'|| !/^[a-f0-9]{32}$/.test(nonce) ||
       typeof signature!=='string'|| !/^[A-Za-z0-9_-]{90,100}$/.test(signature)) throw Error();
    const publicKey=createPublicKey({key:Buffer.from(key,'base64url'),type:'spki',format:'der'});
    if(publicKey.asymmetricKeyType!=='ec'||publicKey.asymmetricKeyDetails?.namedCurve!=='prime256v1') throw Error();
    if(!verify('sha256',Buffer.from(canonical(time,nonce,raw)),publicKey,Buffer.from(signature,'base64url'))) throw Error();
    return {id:sha256(publicKey.export({type:'spki',format:'der'})),nonce};
  } catch {throw new HttpError(401,'invalid_installation_proof');}
}
// Replay protection and per-install/IP limits are atomic across all function instances.
export const installationQuotaScript=`
if redis.call('EXISTS', KEYS[1]) == 1 then return -1 end
for i=2,5 do
 if tonumber(redis.call('GET',KEYS[i]) or '0') >= tonumber(ARGV[i-1]) then return 0 end
end
redis.call('SET',KEYS[1],'1','EX',180)
for i=2,5 do
 redis.call('INCR',KEYS[i]); redis.call('EXPIRE',KEYS[i],tonumber(ARGV[i+3]))
end
return 1`;
export async function reserveInstallation(identity, ip, env=process.env, fetcher=fetch, now=Date.now()) {
  if(!isIP(ip||'')) throw new HttpError(503,'client_address_unavailable');
  const url=env.NOTOUCH2_KV_REST_API_URL||env.UPSTASH_REDIS_REST_URL||env.KV_REST_API_URL;
  const token=env.NOTOUCH2_KV_REST_API_TOKEN||env.UPSTASH_REDIS_REST_TOKEN||env.KV_REST_API_TOKEN;
  if(!/^https:\/\/[^/]+\/?$/.test(url||'')||!token) throw new HttpError(503,'quota_store_unavailable');
  const minute=Math.floor(now/60000),day=Math.floor(now/86400000);
  const ipHash=sha256(`${day}:${ip}`);
  const keys=[`notouch:proof:${identity.id}:${identity.nonce}`,
    `notouch:install:m:${identity.id}:${minute}`,`notouch:install:d:${identity.id}:${day}`,
    `notouch:ip:m:${ipHash}:${minute}`,`notouch:ip:d:${ipHash}:${day}`];
  try {
    const response=await fetcher(url,{method:'POST',redirect:'error',signal:AbortSignal.timeout(4000),
      headers:{Authorization:`Bearer ${token}`,'Content-Type':'application/json'},
      body:JSON.stringify(['EVAL',installationQuotaScript,'5',...keys,'6','50','12','100','120','172800','120','172800'])});
    if(!response.ok) throw Error();
    const result=await response.json();
    if(result.error|| ![-1,0,1].includes(result.result)) throw Error();
    if(result.result===-1) throw new HttpError(409,'replayed_request');
    if(result.result===0) throw new HttpError(429,'call_limit_reached');
  } catch(e) {if(e instanceof HttpError) throw e;throw new HttpError(503,'quota_store_unavailable');}
}
