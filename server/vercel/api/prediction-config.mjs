import {config} from '../predictions.mjs';
export default function handler(req,res) {
  res.setHeader('Cache-Control','no-store');
  if(req.method!=='GET') return res.status(405).json({detail:'method_not_allowed'});
  return res.status(200).json(config());
}
