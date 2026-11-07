export function historyFilters(input:{resourceType:string;resourceId:string;actorId:string;from:string;until:string}) {
  const params=new URLSearchParams({size:'50'})
  for(const key of ['resourceType','resourceId','actorId'] as const) if(input[key].trim())params.set(key,input[key].trim())
  const from=input.from?new Date(input.from+'+09:00'):null
  const until=input.until?new Date(input.until+'+09:00'):null
  if((from&&Number.isNaN(from.getTime()))||(until&&Number.isNaN(until.getTime()))||(from&&until&&from>=until))throw new Error('시작은 종료보다 이른 시각이어야 합니다.')
  if(from)params.set('from',from.toISOString())
  if(until)params.set('until',until.toISOString())
  return params.toString()
}
export function displayTime(value:string) {return new Intl.DateTimeFormat('ko-KR',{timeZone:'Asia/Seoul',dateStyle:'medium',timeStyle:'medium'}).format(new Date(value))}
