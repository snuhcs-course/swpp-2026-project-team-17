import {publicPredict} from '../public-predictions.mjs';
import {HttpError} from '../core.mjs';
// Preserve exact bytes: JSON reserialization would invalidate Android signatures.
export const config={api:{bodyParser:false}};
export default async function handler(req,res) {
 res.setHeader('Cache-Control','no-store');
 if(req.method!=='POST') return res.status(405).json({detail:'method_not_allowed'});
 try {
  if(!/^application\/json(?:;|$)/i.test(req.headers['content-type']||'')) throw new HttpError(415,'json_required');
  const parts=[];let size=0;
  for await(const chunk of req) {size+=chunk.length;if(size>16384) throw new HttpError(413,'request_too_large');parts.push(Buffer.from(chunk));}
  // Vercel overwrites these headers at its trusted edge. Never accept an IP in the JSON body.
  const ip=(req.headers['x-vercel-forwarded-for']||req.headers['x-forwarded-for']||'').split(',')[0].trim();
  return res.status(200).json(await publicPredict(Buffer.concat(parts),req.headers,ip));
 } catch(e) {return res.status(e instanceof HttpError?e.status:500).json({detail:e instanceof HttpError?e.message:'internal_error'});}
}
