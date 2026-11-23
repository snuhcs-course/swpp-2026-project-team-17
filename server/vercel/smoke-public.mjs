// Explicit live smoke test: one valid request may incur provider usage.
import {generateKeyPairSync,randomBytes,sign} from 'node:crypto';
import {canonical} from './installation-auth.mjs';
const base=process.argv[2]||'https://notouch-server.vercel.app';
if(!base.startsWith('https://')) throw Error('HTTPS required');
const policyResponse=await fetch(base+'/v2/config');
console.log('config',policyResponse.status);
if(!policyResponse.ok) process.exit(1);
const policy=await policyResponse.json();
if(!policy.enabled||policy.auth_scheme!=='installation-p256-v1') throw Error('Server not ready');
const {publicKey,privateKey}=generateKeyPairSync('ec',{namedCurve:'prime256v1'});
const time=String(Date.now()),nonce=randomBytes(16).toString('hex');
const body=JSON.stringify({text:'안녕하세요',version:1,policy_id:policy.policy_id});
const headers={'Content-Type':'application/json','X-NoTouch-Key':publicKey.export({format:'der',type:'spki'}).toString('base64url'),
 'X-NoTouch-Time':time,'X-NoTouch-Nonce':nonce,'X-NoTouch-Signature':sign('sha256',Buffer.from(canonical(time,nonce,Buffer.from(body))),privateKey).toString('base64url')};
async function send(label,h,b,expected) {
 const r=await fetch(base+'/v2/predictions',{method:'POST',headers:h,body:b});
 const data=await r.json();console.log(label,r.status,JSON.stringify({detail:data.detail,version:data.version,words:Array.isArray(data.words)?data.words.length:undefined}));
 if(r.status!==expected) throw Error(label+' unexpected status');
}
await send('unsigned',{'Content-Type':'application/json'},body,401);
await send('tampered',headers,body+' ',401);
await send('valid',headers,body,200);
await send('replay',headers,body,409);
const legacy=await fetch(base+'/v1/config');console.log('legacy config',legacy.status);
if(legacy.status!==200) throw Error('Legacy regression');
