import { options as configuration,request,summary } from './common.js';
export const options=configuration();
export default function(){request('/slow/item');}
export const handleSummary=summary;
