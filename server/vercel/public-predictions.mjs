import {config,predictAuthorized} from './predictions.mjs';
import {sha256,verifyProof,reserveInstallation} from './installation-auth.mjs';
import {HttpError} from './core.mjs';
export function publicConfig(env=process.env) {
  const base=config(env);
  const baseNotice='NoTouch에서 작성 중인 초안만 OpenAI로 보내며 다른 앱의 내용은 보내지 않습니다. 서버는 초안 본문을 저장하지 않으며 OpenAI store:false를 사용합니다. 제공자의 악용 방지 로그는 기본 최대 30일 보관될 수 있습니다. 설치별 공개키 식별자와 IP 주소 해시, 사용량은 부정 사용 방지를 위해 최대 48시간 저장합니다. 설치별 분당 6회·하루 50회, 전체 분당 12회·하루 200회 제한으로 예측을 사용할 수 없을 때도 직접 입력은 가능합니다.';
  const notice=env.NOTOUCH_PREDICTION_LIMITS === 'on' ? baseNotice : baseNotice.replace('설치별 분당 6회·하루 50회, 전체 분당 12회·하루 200회 제한으로 예측을 사용할 수 없을 때도 직접 입력은 가능합니다.', '중계 서버의 기기별·IP별·전체 문자 예측 호출 횟수 제한은 해제되어 있습니다. 제공자 측 제한이나 장애로 예측을 사용할 수 없을 때도 직접 입력은 가능합니다.');
  return {...base,enabled:Boolean(env.OPENAI_API_KEY&&(env.NOTOUCH2_KV_REST_API_TOKEN||env.UPSTASH_REDIS_REST_TOKEN||env.KV_REST_API_TOKEN)),notice,
    auth_scheme:'installation-p256-v1',policy_id:sha256(`texting-v2|${base.model}|${notice}`)};
}
export async function publicPredict(raw,headers,ip,env=process.env,fetcher=fetch,now=Date.now()) {
  if(!Buffer.isBuffer(raw)||raw.length>16384) throw new HttpError(413,'request_too_large');
  const identity=verifyProof(headers,raw,now);
  let body;try {body=JSON.parse(raw.toString('utf8'));}catch {throw new HttpError(422,'invalid_request');}
  const policy=publicConfig(env);
  if(!body||typeof body!=='object'||Object.keys(body).sort().join(',')!=='policy_id,text,version'||typeof body.text!=='string'||!body.text.trim()||body.text.length>2000||!Number.isSafeInteger(body.version)||body.version<0) throw new HttpError(422,'invalid_request');
  if(body.policy_id!==policy.policy_id) throw new HttpError(412,'consent_required');
  if(!policy.enabled) throw new HttpError(503,'prediction_not_configured');
  await reserveInstallation(identity,ip,env,fetcher,now);
  return predictAuthorized(body,env,fetcher,policy);
}
