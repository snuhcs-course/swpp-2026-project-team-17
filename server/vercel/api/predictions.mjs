import {predict} from '../predictions.mjs';
import {HttpError} from '../core.mjs';
export default async function handler(req,res) {
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='POST') return res.status(405).json({detail:'method_not_allowed'});
  try {
    if(!String(req.headers['content-type']).startsWith('application/json')) throw new HttpError(415,'json_required');
    if(Buffer.byteLength(typeof req.body==='string'?req.body:JSON.stringify(req.body)||'')>16384) throw new HttpError(413,'body_size');
    let body=req.body;
    if(typeof body==='string') {try{body=JSON.parse(body)}catch{throw new HttpError(422,'invalid_request')}}
    return res.status(200).json(await predict(body,req.headers.authorization));
  } catch(e) {return res.status(e instanceof HttpError?e.status:500).json({detail:e instanceof HttpError?e.message:'internal_error'});}
}
